package org.spectrumauctions.sats.opt.xor;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.math.DoubleMath;
import edu.harvard.econcs.jopt.solver.IMIP;
import edu.harvard.econcs.jopt.solver.IMIPResult;
import edu.harvard.econcs.jopt.solver.IMIPSolver;
import edu.harvard.econcs.jopt.solver.client.SolverClient;
import edu.harvard.econcs.jopt.solver.mip.*;
import org.spectrumauctions.sats.core.bidlang.xor.XORBid;
import org.spectrumauctions.sats.core.bidlang.xor.XORValue;
import org.spectrumauctions.sats.core.model.SATSBidder;
import org.spectrumauctions.sats.core.model.Bundle;
import org.spectrumauctions.sats.core.model.SATSGood;
import org.spectrumauctions.sats.core.model.World;
import org.spectrumauctions.sats.opt.domain.SATSAllocation;
import org.spectrumauctions.sats.opt.domain.ItemSATSAllocation;
import org.spectrumauctions.sats.opt.domain.WinnerDeterminator;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static edu.harvard.econcs.jopt.solver.mip.MIP.MAX_VALUE;

/**
 * Wraps an OR or OR* winner determination
 *
 * @author Benedikt Buenz
 */
public class XORWinnerDetermination<T extends SATSGood> implements WinnerDeterminator<T> {
    private Map<XORValue<T>, Variable> bidVariables = new HashMap<>();
    private Collection<XORBid<T>> bids;
    private IMIP winnerDeterminationProgram;
    private SATSAllocation<T> result = null;
    private World world;
    private double scalingFactor = 1;


    public XORWinnerDetermination(Collection<XORBid<T>> bids) {
        Preconditions.checkNotNull(bids);
        Preconditions.checkArgument(!bids.isEmpty());
        this.bids = bids;
        double maxValue = -1;
        for (XORBid<T> bid : bids) {
            for (XORValue<T> value : bid.getValues()) {
                if (value.value().doubleValue() > maxValue) {
                    maxValue = value.value().doubleValue();
                }
            }
        }
        if (maxValue > MIP.MAX_VALUE * 0.9) {
            this.scalingFactor = 0.9 / maxValue * MIP.MAX_VALUE;
        }
        this.world = bids.iterator().next().getBidder().getWorld();
        winnerDeterminationProgram = createWinnerDeterminationMIP();
    }

    private IMIP createWinnerDeterminationMIP() {
        MIP wdp = new MIP();
        wdp.setObjectiveMax(true);
        // Add decision variables and objective terms:
        for (XORBid<T> xorBid : bids) {
            for (XORValue<T> bundleBid : xorBid.getValues()) {
                Variable bidI = new Variable("Bid " + bundleBid.getId(), VarType.BOOLEAN, 0, 1);
                wdp.add(bidI);
                wdp.addObjectiveTerm(bundleBid.value().doubleValue() * scalingFactor, bidI);
                bidVariables.put(bundleBid, bidI);
            }
        }
        Map<SATSGood, Constraint> goods = new HashMap<>();

        for (XORBid<T> xorBid : bids) {
            Constraint exclusiveBids = new Constraint(CompareType.LEQ, 1);
            for (XORValue<T> bundleBid : xorBid.getValues()) {
                exclusiveBids.addTerm(1, bidVariables.get(bundleBid));
                for (SATSGood good : bundleBid.getLicenses()) {
                    Constraint noDoubleAssignment = goods.get(good);
                    if (noDoubleAssignment == null) {
                        noDoubleAssignment = new Constraint(CompareType.LEQ, 1);
                        goods.put(good, noDoubleAssignment);
                    }
                    noDoubleAssignment.addTerm(1.0, bidVariables.get(bundleBid));
                }
            }
            wdp.add(exclusiveBids);
        }
        for (Constraint noDoubleAssignments : goods.values()) {
            wdp.add(noDoubleAssignments);
        }

        return wdp;
    }

    protected IMIP getMIP() {
        return winnerDeterminationProgram;
    }

    private Variable getBidVariable(XORValue<T> bundleBid) {
        return bidVariables.get(bundleBid);
    }

    @Override
    public WinnerDeterminator<T> getWdWithoutBidder(SATSBidder bidder) {
        return new XORWinnerDetermination<>(bids.stream().filter(b -> !b.getBidder().equals(bidder)).collect(Collectors.toSet()));
    }

    @Override
    public SATSAllocation<T> calculateAllocation() {
        if (result == null) {
            result = solveWinnerDetermination();
        }
        return result;
    }

    @Override
    public WinnerDeterminator<T> copyOf() {
        return new XORWinnerDetermination<>(bids);
    }

    @Override
    public void adjustPayoffs(Map<SATSBidder<T>, Double> payoffs) {
        for (XORBid<T> bidPerBidder : bids) {
            Variable x = new Variable("x_" + bidPerBidder.getBidder().getLongId(), VarType.BOOLEAN, 0, 1);
            winnerDeterminationProgram.add(x);
            winnerDeterminationProgram.addObjectiveTerm(-payoffs.getOrDefault(bidPerBidder.getBidder(), 0.0), x);

            Constraint x1 = new Constraint(CompareType.GEQ, 0);
            Constraint x2 = new Constraint(CompareType.LEQ, 0);
            x1.addTerm(-1, x);
            x2.addTerm(-MAX_VALUE, x);
            bidPerBidder.getValues().forEach(b -> x1.addTerm(1, bidVariables.get(b)));
            bidPerBidder.getValues().forEach(b -> x2.addTerm(1, bidVariables.get(b)));
            winnerDeterminationProgram.add(x1);
            winnerDeterminationProgram.add(x2);
        }
    }

    private SATSAllocation<T> solveWinnerDetermination() {
        IMIPSolver solver = new SolverClient();
        IMIPResult mipResult = solver.solve(getMIP());
        return adaptMIPResult(mipResult);
    }

    private SATSAllocation<T> adaptMIPResult(IMIPResult mipResult) {

        Map<SATSBidder<T>, Bundle<T>> trades = new HashMap<>();
        Map<SATSBidder<T>, BigDecimal> declaredValues = new HashMap<>();
        double totalValue = 0;
        for (XORBid<T> xorBid : bids) {
            double bidValue = 0;
            ImmutableSet.Builder<T> goodsBuilder = ImmutableSet.builder();
            for (XORValue<T> bundleBid : xorBid.getValues()) {
                if (DoubleMath.fuzzyEquals(mipResult.getValue(getBidVariable(bundleBid)), 1, 1e-3)) {
                    goodsBuilder.addAll(bundleBid.getLicenses());
                    totalValue += bundleBid.value().doubleValue();
                    bidValue += bundleBid.value().doubleValue();
                }
            }
            Set<T> goods = goodsBuilder.build();
            if (!goods.isEmpty()) {
                trades.put(xorBid.getBidder(), new Bundle<>(goods));
                declaredValues.put(xorBid.getBidder(), BigDecimal.valueOf(bidValue));
            }
        }

        ItemSATSAllocation.ItemAllocationBuilder<T> builder = new ItemSATSAllocation.ItemAllocationBuilder<>();
        return builder
                .withAllocation(trades)
                .withTotalValue(BigDecimal.valueOf(totalValue))
                .withDeclaredValues(declaredValues)
                .withWorld(world).build();
    }

}
