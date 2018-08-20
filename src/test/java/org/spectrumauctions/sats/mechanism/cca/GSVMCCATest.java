package org.spectrumauctions.sats.mechanism.cca;

import com.google.common.collect.Lists;
import edu.harvard.econcs.jopt.solver.SolveParam;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Ignore;
import org.junit.Test;
import org.spectrumauctions.sats.core.bidlang.xor.XORBid;
import org.spectrumauctions.sats.core.bidlang.xor.XORValue;
import org.spectrumauctions.sats.core.model.Bidder;
import org.spectrumauctions.sats.core.model.gsvm.GSVMBidder;
import org.spectrumauctions.sats.core.model.gsvm.GSVMLicense;
import org.spectrumauctions.sats.core.model.gsvm.GlobalSynergyValueModel;
import org.spectrumauctions.sats.mechanism.cca.priceupdate.SimpleRelativeNonGenericPriceUpdate;
import org.spectrumauctions.sats.mechanism.cca.supplementaryround.LastBidsTrueValueNonGenericSupplementaryRound;
import org.spectrumauctions.sats.mechanism.cca.supplementaryround.ProfitMaximizingNonGenericSupplementaryRound;
import org.spectrumauctions.sats.opt.domain.Allocation;
import org.spectrumauctions.sats.opt.domain.ItemAllocation;
import org.spectrumauctions.sats.opt.model.gsvm.GSVMStandardMIP;
import org.spectrumauctions.sats.opt.model.gsvm.demandquery.GSVM_DemandQueryMIPBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class GSVMCCATest {

    private static final Logger logger = LogManager.getLogger(GSVMCCATest.class);

    private static int ITERATIONS = 5;

    @Test
    @Ignore
    public void testMultipleInstances() {
        for (int i = 0; i < ITERATIONS; i++) {
            logger.info("Starting round {} of {}...", i + 1, ITERATIONS);
            testClockPhaseVsSupplementaryPhaseEfficiency();
        }
    }

    private void testClockPhaseVsSupplementaryPhaseEfficiency() {
        List<GSVMBidder> rawBidders = new GlobalSynergyValueModel().createNewPopulation();
        GSVMStandardMIP mip = new GSVMStandardMIP(Lists.newArrayList(rawBidders));
        mip.getMip().setSolveParam(SolveParam.RELATIVE_OBJ_GAP, 1e-5);
        ItemAllocation<GSVMLicense> efficientAllocation = mip.calculateAllocation();
        Allocation<GSVMLicense> efficientAllocationWithTrueValues = efficientAllocation.getAllocationWithTrueValues();
        double diff = efficientAllocation.getTotalValue().doubleValue() - efficientAllocationWithTrueValues.getTotalValue().doubleValue();
        assertTrue(diff > -1e-6 && diff < 1e-6);

        long start = System.currentTimeMillis();
        NonGenericCCAMechanism<GSVMLicense> cca = getMechanism(rawBidders);

        Allocation<GSVMLicense> allocationAfterClockPhase = cca.calculateClockPhaseAllocation();
        Allocation<GSVMLicense> allocCP = allocationAfterClockPhase.getAllocationWithTrueValues();
        assertNotEquals(allocationAfterClockPhase, allocCP);

        Allocation<GSVMLicense> allocationAfterSupplementaryRound = cca.calculateAllocationAfterSupplementaryRound();
        Allocation<GSVMLicense> allocSR = allocationAfterSupplementaryRound.getAllocationWithTrueValues();
        assertNotEquals(allocationAfterSupplementaryRound, allocSR);
        long end = System.currentTimeMillis();

        logger.info("Total rounds: {}", cca.getTotalRounds());
        logger.info("(Supply - Demand) of final round: {}", cca.getSupplyMinusDemand());
        logger.info("Bids after clock phase per bidder: {}", cca.getBidCountAfterClockPhase());
        logger.info("Bids after supplementary round per bidder: {}", cca.getBidCountAfterSupplementaryRound());
        logger.info("CCA took {}s.", (end - start) / 1000);

        BigDecimal qualityCP = allocCP.getTotalValue().divide(efficientAllocationWithTrueValues.getTotalValue(), RoundingMode.HALF_UP);
        logger.info("Quality after clock phase: {}", qualityCP.setScale(4, RoundingMode.HALF_UP));

        BigDecimal qualitySR = allocSR.getTotalValue().divide(efficientAllocationWithTrueValues.getTotalValue(), RoundingMode.HALF_UP);
        logger.info("Quality with supplementary round: {}", qualitySR.setScale(4, RoundingMode.HALF_UP));

        assertTrue(qualityCP.compareTo(qualitySR) < 1);
        assertTrue(qualitySR.compareTo(BigDecimal.ONE) < 1);
    }

    private NonGenericCCAMechanism<GSVMLicense> getMechanism(List<GSVMBidder> rawBidders) {
        List<Bidder<GSVMLicense>> bidders = rawBidders.stream()
                .map(b -> (Bidder<GSVMLicense>) b).collect(Collectors.toList());
        NonGenericCCAMechanism<GSVMLicense> cca = new NonGenericCCAMechanism<>(bidders, new GSVM_DemandQueryMIPBuilder());
        cca.setStartingPrice(BigDecimal.ZERO);
        cca.setEpsilon(1e-5);
        cca.setClockPhaseNumberOfBundles(3);

        SimpleRelativeNonGenericPriceUpdate<GSVMLicense> priceUpdater = new SimpleRelativeNonGenericPriceUpdate<>();
        priceUpdater.setPriceUpdate(BigDecimal.valueOf(0.1));
        priceUpdater.setInitialUpdate(BigDecimal.valueOf(0.5));
        cca.setPriceUpdater(priceUpdater);

        ProfitMaximizingNonGenericSupplementaryRound<GSVMLicense> supplementaryRound = new ProfitMaximizingNonGenericSupplementaryRound<>();
        supplementaryRound.setNumberOfSupplementaryBids(500);
        cca.addSupplementaryRound(supplementaryRound);

        return cca;
    }

    @Test
    public void testMultipleSupplementaryRounds() {
        List<GSVMBidder> rawBidders = new GlobalSynergyValueModel().createNewPopulation();
        NonGenericCCAMechanism<GSVMLicense> cca = getMechanism(rawBidders);

        ProfitMaximizingNonGenericSupplementaryRound<GSVMLicense> supplementaryRoundLastPrices = new ProfitMaximizingNonGenericSupplementaryRound<>();
        supplementaryRoundLastPrices.setNumberOfSupplementaryBids(150);
        supplementaryRoundLastPrices.useLastDemandedPrices(true);
        cca.addSupplementaryRound(supplementaryRoundLastPrices);

        Allocation<GSVMLicense> allocationAfterSupplementaryRound = cca.calculateAllocationAfterSupplementaryRound();
        rawBidders.forEach(b -> assertEquals(cca.getBidCountAfterSupplementaryRound().get(b) - cca.getBidCountAfterClockPhase().get(b), 650));
    }

    @Test
    public void testLastBidsSupplementaryRound() {
        List<GSVMBidder> rawBidders = new GlobalSynergyValueModel().createNewPopulation();
        List<Bidder<GSVMLicense>> bidders = rawBidders.stream()
                .map(b -> (Bidder<GSVMLicense>) b).collect(Collectors.toList());
        NonGenericCCAMechanism<GSVMLicense> cca = new NonGenericCCAMechanism<>(bidders, new GSVM_DemandQueryMIPBuilder());
        cca.setStartingPrice(BigDecimal.ZERO);
        cca.setEpsilon(1e-5);

        SimpleRelativeNonGenericPriceUpdate<GSVMLicense> priceUpdater = new SimpleRelativeNonGenericPriceUpdate<>();
        priceUpdater.setPriceUpdate(BigDecimal.valueOf(0.1));
        priceUpdater.setInitialUpdate(BigDecimal.valueOf(0.5));
        cca.setPriceUpdater(priceUpdater);

        LastBidsTrueValueNonGenericSupplementaryRound<GSVMLicense> lastBidsSupplementaryRound = new LastBidsTrueValueNonGenericSupplementaryRound<>();
        lastBidsSupplementaryRound.setNumberOfSupplementaryBids(10);
        cca.addSupplementaryRound(lastBidsSupplementaryRound);

        Allocation<GSVMLicense> allocationAfterSupplementaryRound = cca.calculateAllocationAfterSupplementaryRound();
        for (GSVMBidder bidder : rawBidders) {
            XORBid<GSVMLicense> bid = cca.getBidAfterSupplementaryRound(bidder);
            int maxBids = Math.min(10, bid.getValues().size() / 2);
            int count = 0;
            for (int i = bid.getValues().size() - 1; i > 0 && count++ < maxBids; i--) {
                XORValue<GSVMLicense> current = bid.getValues().get(i);
                int baseIndex = 2 * bid.getValues().size() - 2*maxBids - 1 - i;
                XORValue<GSVMLicense> base = bid.getValues().get(baseIndex);
                assertEquals(current.getLicenses(), base.getLicenses());
                assertTrue(current.value().compareTo(base.value()) > 0);
            }
        }
    }
}
