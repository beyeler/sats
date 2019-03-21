package org.spectrumauctions.sats.mechanism.ccg;

import edu.harvard.econcs.jopt.solver.IMIPResult;
import edu.harvard.econcs.jopt.solver.client.SolverClient;
import edu.harvard.econcs.jopt.solver.mip.*;
import org.spectrumauctions.sats.core.model.SATSBidder;
import org.spectrumauctions.sats.core.model.SATSGood;
import org.spectrumauctions.sats.mechanism.domain.BidderPayment;
import org.spectrumauctions.sats.mechanism.domain.MechanismResult;
import org.spectrumauctions.sats.mechanism.domain.Payment;
import org.spectrumauctions.sats.mechanism.domain.mechanisms.AuctionMechanism;
import org.spectrumauctions.sats.mechanism.vcg.VCGMechanism;
import org.spectrumauctions.sats.opt.domain.SATSAllocation;
import org.spectrumauctions.sats.opt.domain.WinnerDeterminator;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

public class CCGMechanism<T extends SATSGood> implements AuctionMechanism<T> {

    private WinnerDeterminator<T> baseWD;
    private MechanismResult<T> result;
    private BigDecimal scale = BigDecimal.ONE;

    public void setScale(BigDecimal scale) {
        this.scale = scale;
    }

    public CCGMechanism(WinnerDeterminator<T> wdp) {
        this.baseWD = wdp;
    }

    @Override
    public MechanismResult<T> getMechanismResult() {
        if (result == null) {
            result = calculateCCGPayments();
        }
        return result;
    }

    @Override
    public Payment<T> getPayment() {
        return getMechanismResult().getPayment();
    }

    @Override
    public WinnerDeterminator<T> getWdWithoutBidder(SATSBidder<T> bidder) {
        return baseWD.getWdWithoutBidder(bidder);
    }

    @Override
    public SATSAllocation<T> calculateAllocation() {
        return getMechanismResult().getAllocation();
    }

    @Override
    public WinnerDeterminator<T> copyOf() {
        return baseWD.copyOf();
    }

    @Override
    public void adjustPayoffs(Map<SATSBidder<T>, Double> payoffs) {
        baseWD.adjustPayoffs(payoffs);
    }

    private MechanismResult<T> calculateCCGPayments() {
        MechanismResult<T> vcgResult = new VCGMechanism<>(baseWD).getMechanismResult();
        SATSAllocation<T> originalAllocation = vcgResult.getAllocation();

        SolverClient solverClient = new SolverClient();

        Payment<T> payment = vcgResult.getPayment();

        Map<SATSBidder<T>, Variable> paymentVariables = createPaymentVariables(originalAllocation, payment);
        MIP l1Mip = new MIP();
        paymentVariables.values().forEach(l1Mip::add);
        paymentVariables.values().forEach(v -> l1Mip.addObjectiveTerm(1, v));

        double oldBlockingCoalitionValue = -1;
        boolean caughtInLoopDueToRoundingErrors = false;
        while (true) {
            Map<SATSBidder<T>, Double> payoffs = computePayoffs(originalAllocation, payment);
            WinnerDeterminator<T> blockingCoalitionMip = baseWD.copyOf();
            blockingCoalitionMip.adjustPayoffs(payoffs);

            SATSAllocation<T> blockingCoalition = blockingCoalitionMip.calculateAllocation();

            double traitorPayoffs = 0;
            double traitorPayments = 0;
            for (SATSBidder<T> bidder : blockingCoalition.getWinners()) {
                traitorPayoffs += (originalAllocation.getTradeValue(bidder).doubleValue() - payment.paymentOf(bidder).getAmount());
                traitorPayments += payment.paymentOf(bidder).getAmount();
            }

            double z_p = blockingCoalition.getTotalValue().doubleValue() - traitorPayoffs;
            if (oldBlockingCoalitionValue == z_p) {
                caughtInLoopDueToRoundingErrors = true;
            } else {
                oldBlockingCoalitionValue = z_p;
            }
            double payments = payment.getTotalPayments();
            if (caughtInLoopDueToRoundingErrors ||
                    z_p <= payments + 1e-6) {
                Map<SATSBidder<T>, BidderPayment> unscaledPaymentMap = new HashMap<>();
                for (Map.Entry<SATSBidder<T>, BidderPayment> entry : payment.getPaymentMap().entrySet()) {
                    unscaledPaymentMap.put(entry.getKey(), new BidderPayment(entry.getValue().getAmount() / scale.doubleValue()));
                }
                Payment<T> unscaledPayment = new Payment<>(unscaledPaymentMap);
                return new MechanismResult<>(unscaledPayment, originalAllocation);
            } else {
                double coalitionValue = z_p - traitorPayments;
                Constraint constraint = new Constraint(CompareType.GEQ, coalitionValue);

                for (SATSBidder<T> nonTraitor : originalAllocation.getWinners()) {
                    if (!blockingCoalition.getWinners().contains(nonTraitor)) {
                        Variable paymentVariable = paymentVariables.get(nonTraitor);
                        constraint.addTerm(1, paymentVariable);
                    }
                }

                l1Mip.add(constraint);

                // L1 Norm
                IMIPResult l1Result = solverClient.solve(l1Mip);

                // L2 Norm
                MIP l2Mip = new MIP();
                l1Mip.getVars().values().forEach(l2Mip::add);
                l1Mip.getConstraints().forEach(l2Mip::add);

                double totalPayments = l1Result.getObjectiveValue();
                Constraint fixPayments1 = new Constraint(CompareType.LEQ, totalPayments + 1e-6);
                Constraint fixPayments2 = new Constraint(CompareType.GEQ, totalPayments - 1e-6);
                paymentVariables.values().forEach(v -> fixPayments1.addTerm(1, v));
                paymentVariables.values().forEach(v -> fixPayments2.addTerm(1, v));
                l2Mip.add(fixPayments1);
                l2Mip.add(fixPayments2);

                for (Map.Entry<SATSBidder<T>, Variable> entry : paymentVariables.entrySet()) {
                    Variable winnerVariable = entry.getValue();
                    l2Mip.addObjectiveTerm(1, winnerVariable, winnerVariable);
                    l2Mip.addObjectiveTerm(-2 * vcgResult.getPayment().paymentOf(entry.getKey()).getAmount(), winnerVariable);
                }

                IMIPResult l2Result = solverClient.solve(l2Mip);

                Map<SATSBidder<T>, BidderPayment> paymentMap = new HashMap<>(originalAllocation.getWinners().size());
                for (SATSBidder<T> company : originalAllocation.getWinners()) {
                    double doublePayment = l2Result.getValue(paymentVariables.get(company));
                    paymentMap.put(company, new BidderPayment(doublePayment));
                }
                payment = new Payment<>(paymentMap);
            }
        }

    }

    private Map<SATSBidder<T>, Double> computePayoffs(SATSAllocation<T> allocation, Payment<T> payment) {
        Map<SATSBidder<T>, Double> payoffs = new HashMap<>(allocation.getWinners().size());
        for (SATSBidder<T> company : allocation.getWinners()) {
            payoffs.put(company, allocation.getTradeValue(company).doubleValue() - payment.paymentOf(company).getAmount());
        }
        return payoffs;
    }

    private Map<SATSBidder<T>, Variable> createPaymentVariables(SATSAllocation<T> originalAllocation, Payment<T> payment) {
        Map<SATSBidder<T>, Variable> winnerVariables = new HashMap<>(originalAllocation.getWinners().size());
        for (SATSBidder<T> winner : originalAllocation.getWinners()) {

            double winnerPayment = payment.paymentOf(winner).getAmount();
            double winnerValue = originalAllocation.getTradeValue(winner).doubleValue();
            Variable winnerVariable = new Variable(String.valueOf(winner.getLongId()),
                    VarType.DOUBLE,
                    0 /* FIXME: For MRVM, the payment sometimes is > 0 even if the value is 0. Bug or normal? */,
                    winnerValue);

            winnerVariables.put(winner, winnerVariable);
        }
        return winnerVariables;
    }

}
