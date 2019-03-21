package org.spectrumauctions.sats.mechanism.cca;

import com.google.common.collect.Lists;
import edu.harvard.econcs.jopt.solver.SolveParam;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Ignore;
import org.junit.Test;
import org.spectrumauctions.sats.core.bidlang.xor.XORBid;
import org.spectrumauctions.sats.core.bidlang.xor.XORValue;
import org.spectrumauctions.sats.core.model.SATSBidder;
import org.spectrumauctions.sats.core.model.SATSGood;
import org.spectrumauctions.sats.core.model.IncompatibleWorldException;
import org.spectrumauctions.sats.core.model.gsvm.GSVMBidder;
import org.spectrumauctions.sats.core.model.gsvm.GSVMLicense;
import org.spectrumauctions.sats.core.model.gsvm.GlobalSynergyValueModel;
import org.spectrumauctions.sats.mechanism.cca.priceupdate.SimpleRelativeNonGenericPriceUpdate;
import org.spectrumauctions.sats.mechanism.cca.supplementaryround.LastBidsTrueValueNonGenericSupplementaryRound;
import org.spectrumauctions.sats.mechanism.cca.supplementaryround.ProfitMaximizingNonGenericSupplementaryRound;
import org.spectrumauctions.sats.opt.domain.*;
import org.spectrumauctions.sats.opt.domain.ItemSATSAllocation;
import org.spectrumauctions.sats.opt.domain.SATSAllocation;
import org.spectrumauctions.sats.opt.model.gsvm.GSVMStandardMIP;
import org.spectrumauctions.sats.opt.model.gsvm.demandquery.GSVM_DemandQueryMIPBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

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
        mip.getMIP().setSolveParam(SolveParam.RELATIVE_OBJ_GAP, 1e-5);
        ItemSATSAllocation<GSVMLicense> efficientAllocation = mip.calculateAllocation();
        SATSAllocation<GSVMLicense> efficientAllocationWithTrueValues = efficientAllocation.getAllocationWithTrueValues();
        double diff = efficientAllocation.getTotalValue().doubleValue() - efficientAllocationWithTrueValues.getTotalValue().doubleValue();
        assertTrue(diff > -1e-6 && diff < 1e-6);

        long start = System.currentTimeMillis();
        NonGenericCCAMechanism<GSVMLicense> cca = getMechanism(rawBidders);

        SATSAllocation<GSVMLicense> allocationAfterClockPhase = cca.calculateClockPhaseAllocation();
        SATSAllocation<GSVMLicense> allocCP = allocationAfterClockPhase.getAllocationWithTrueValues();
        assertNotEquals(allocationAfterClockPhase, allocCP);

        SATSAllocation<GSVMLicense> allocationAfterSupplementaryRound = cca.calculateAllocationAfterSupplementaryRound();
        SATSAllocation<GSVMLicense> allocSR = allocationAfterSupplementaryRound.getAllocationWithTrueValues();
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
        List<SATSBidder<GSVMLicense>> bidders = rawBidders.stream()
                .map(b -> (SATSBidder<GSVMLicense>) b).collect(Collectors.toList());
        NonGenericCCAMechanism<GSVMLicense> cca = new NonGenericCCAMechanism<>(bidders, new GSVM_DemandQueryMIPBuilder());
        cca.setFallbackStartingPrice(BigDecimal.ZERO);
        cca.setEpsilon(1e-5);

        SimpleRelativeNonGenericPriceUpdate<GSVMLicense> priceUpdater = new SimpleRelativeNonGenericPriceUpdate<>();
        priceUpdater.setPriceUpdate(BigDecimal.valueOf(0.1));
        priceUpdater.setInitialUpdate(BigDecimal.valueOf(0.5));
        cca.setPriceUpdater(priceUpdater);

        ProfitMaximizingNonGenericSupplementaryRound<GSVMLicense> supplementaryRound = new ProfitMaximizingNonGenericSupplementaryRound<>();
        supplementaryRound.setNumberOfSupplementaryBids(100);
        cca.addSupplementaryRound(supplementaryRound);

        return cca;
    }

    @Test
    public void testNoDuplicatesInSupplementaryRound() {
        List<GSVMBidder> rawBidders = new GlobalSynergyValueModel().createNewPopulation();
        NonGenericCCAMechanism<GSVMLicense> cca = getMechanism(rawBidders);
        cca.calculateSampledStartingPrices(50, 100 ,0.1);
        cca.setTimeLimit(30);

        for (GSVMBidder bidder : rawBidders) {
            List<XORValue<GSVMLicense>> valuesCP = cca.getBidsAfterClockPhase()
                    .stream()
                    .filter(bid -> bid.getBidder().equals(bidder))
                    .map(XORBid::getValues)
                    .findFirst().orElseThrow(NoSuchElementException::new);
            List<XORValue<GSVMLicense>> valuesSR = new ArrayList<>(
                    cca.getBidsAfterSupplementaryRound()
                    .stream()
                    .filter(bid -> bid.getBidder().equals(bidder))
                    .map(XORBid::getValues)
                    .findFirst().orElseThrow(NoSuchElementException::new)
            );
            valuesSR.removeAll(valuesCP);
            for (int i = 0; i < valuesSR.size(); i++) {
                for (int j = i + 1; j < valuesSR.size(); j++) {
                    assertNotEquals(valuesSR.get(i).getLicenses(), valuesSR.get(j).getLicenses());
                }
            }
        }
    }

    @Test
    public void testMultipleSupplementaryRounds() {
        List<GSVMBidder> rawBidders = new GlobalSynergyValueModel().createNewPopulation();
        NonGenericCCAMechanism<GSVMLicense> cca = getMechanism(rawBidders);

        ProfitMaximizingNonGenericSupplementaryRound<GSVMLicense> supplementaryRoundLastPrices = new ProfitMaximizingNonGenericSupplementaryRound<>();
        supplementaryRoundLastPrices.setNumberOfSupplementaryBids(150);
        supplementaryRoundLastPrices.useLastDemandedPrices(true);
        cca.addSupplementaryRound(supplementaryRoundLastPrices);

        SATSAllocation<GSVMLicense> allocationAfterSupplementaryRound = cca.calculateAllocationAfterSupplementaryRound();
        rawBidders.forEach(b -> assertEquals(650, cca.getBidCountAfterSupplementaryRound().get(b) - cca.getBidCountAfterClockPhase().get(b)));
    }

    @Test
    public void testSampledStartingPrices() {
        List<GSVMBidder> rawBidders = new GlobalSynergyValueModel().createNewPopulation();
        NonGenericCCAMechanism<GSVMLicense> ccaZero = getMechanism(rawBidders);
        long startZero = System.currentTimeMillis();
        SATSAllocation<GSVMLicense> allocZero = ccaZero.calculateClockPhaseAllocation();
        BigDecimal zeroTotalValue = allocZero.getAllocationWithTrueValues().getTotalValue();
        long durationZero = System.currentTimeMillis() - startZero;

        NonGenericCCAMechanism<GSVMLicense> ccaSampled = getMechanism(rawBidders);
        ccaSampled.calculateSampledStartingPrices(10, 100, 0.1);
        long startSampled = System.currentTimeMillis();
        SATSAllocation<GSVMLicense> allocSampled = ccaSampled.calculateClockPhaseAllocation();
        BigDecimal sampledTotalValue = allocSampled.getAllocationWithTrueValues().getTotalValue();
        long durationSampled = System.currentTimeMillis() - startSampled;

        assertTrue(ccaZero.getTotalRounds() > ccaSampled.getTotalRounds());
        assertTrue(durationZero > durationSampled);
    }

    @Test
    public void testLastBidsSupplementaryRound() {
        List<GSVMBidder> rawBidders = new GlobalSynergyValueModel().createNewPopulation();
        List<SATSBidder<GSVMLicense>> bidders = rawBidders.stream()
                .map(b -> (SATSBidder<GSVMLicense>) b).collect(Collectors.toList());
        NonGenericCCAMechanism<GSVMLicense> cca = new NonGenericCCAMechanism<>(bidders, new GSVM_DemandQueryMIPBuilder());
        cca.setFallbackStartingPrice(BigDecimal.ZERO);
        cca.setEpsilon(1e-5);

        SimpleRelativeNonGenericPriceUpdate<GSVMLicense> priceUpdater = new SimpleRelativeNonGenericPriceUpdate<>();
        priceUpdater.setPriceUpdate(BigDecimal.valueOf(0.1));
        priceUpdater.setInitialUpdate(BigDecimal.valueOf(0.5));
        cca.setPriceUpdater(priceUpdater);

        LastBidsTrueValueNonGenericSupplementaryRound<GSVMLicense> lastBidsSupplementaryRound = new LastBidsTrueValueNonGenericSupplementaryRound<>();
        lastBidsSupplementaryRound.setNumberOfSupplementaryBids(10);
        cca.addSupplementaryRound(lastBidsSupplementaryRound);

        SATSAllocation<GSVMLicense> allocationAfterSupplementaryRound = cca.calculateAllocationAfterSupplementaryRound();
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

    @Test
    public void testEfficiencyClockPhaseVsSupplementaryRound() {
        List<GSVMBidder> rawBidders = new GlobalSynergyValueModel().createNewPopulation(123456);
        List<SATSBidder<GSVMLicense>> bidders = rawBidders.stream()
                .map(b -> (SATSBidder<GSVMLicense>) b).collect(Collectors.toList());
        NonGenericCCAMechanism<GSVMLicense> cca = new NonGenericCCAMechanism<>(bidders, new GSVM_DemandQueryMIPBuilder());
        cca.setFallbackStartingPrice(BigDecimal.ZERO);
        cca.setEpsilon(1e-5);

        SimpleRelativeNonGenericPriceUpdate<GSVMLicense> priceUpdater = new SimpleRelativeNonGenericPriceUpdate<>();
        priceUpdater.setPriceUpdate(BigDecimal.valueOf(0.1));
        priceUpdater.setInitialUpdate(BigDecimal.valueOf(0.5));
        cca.setPriceUpdater(priceUpdater);

        ProfitMaximizingNonGenericSupplementaryRound<GSVMLicense> supplementaryRound = new ProfitMaximizingNonGenericSupplementaryRound<>();
        supplementaryRound.setNumberOfSupplementaryBids(50);
        cca.addSupplementaryRound(supplementaryRound);

        // Solve mechanism
        SATSAllocation<GSVMLicense> allocationAfterClockPhase = cca.calculateClockPhaseAllocation();
        SATSAllocation<GSVMLicense> allocCP = allocationAfterClockPhase.getAllocationWithTrueValues();
        assertNotEquals(allocationAfterClockPhase, allocCP);
        double efficiencyCP = allocationAfterClockPhase.getTotalValue().doubleValue();
        logger.info("Value CP: {}", efficiencyCP);
        SATSAllocation<GSVMLicense> allocationAfterSupplementaryRound = cca.calculateAllocationAfterSupplementaryRound();
        SATSAllocation<GSVMLicense> allocSR = allocationAfterSupplementaryRound.getAllocationWithTrueValues();
        assertNotEquals(allocationAfterSupplementaryRound, allocSR);
        double efficiencySR = allocationAfterSupplementaryRound.getTotalValue().doubleValue();
        logger.info("Value SR: {}", efficiencySR);
        assertTrue(efficiencySR >= efficiencyCP);
    }


    private Collection<XORBid<GSVMLicense>> runStandardCCA(List<SATSBidder<GSVMLicense>> bidders) {
        NonGenericCCAMechanism<GSVMLicense> cca = new NonGenericCCAMechanism<>(bidders, new GSVM_DemandQueryMIPBuilder());
        cca.setFallbackStartingPrice(BigDecimal.ZERO);
        cca.setEpsilon(1e-5);

        SimpleRelativeNonGenericPriceUpdate<GSVMLicense> priceUpdater = new SimpleRelativeNonGenericPriceUpdate<>();
        priceUpdater.setPriceUpdate(BigDecimal.valueOf(0.1));
        priceUpdater.setInitialUpdate(BigDecimal.valueOf(0.5));
        cca.setPriceUpdater(priceUpdater);

        ProfitMaximizingNonGenericSupplementaryRound<GSVMLicense> supplementaryRound = new ProfitMaximizingNonGenericSupplementaryRound<>();
        supplementaryRound.setNumberOfSupplementaryBids(10);
        cca.addSupplementaryRound(supplementaryRound);

        return cca.getBidsAfterSupplementaryRound();
    }

    @Test
    public void testMinimalExample() {
        List<GSVMBidder> rawBidders = new GlobalSynergyValueModel().createNewPopulation(123456);
        // The following line used to make a difference in the solution pool and thus the allocation, but it does not anymore
        // new FindJoptTest().joptLibrarySimpleExample();
        List<SATSBidder<GSVMLicense>> bidders = rawBidders.stream()
                .map(b -> (SATSBidder<GSVMLicense>) b).collect(Collectors.toList());
        SATSBidder<GSVMLicense> firstBidder = bidders.get(0);
        NonGenericCCAMechanism<GSVMLicense> cca = new NonGenericCCAMechanism<>(bidders, new GSVM_DemandQueryMIPBuilder());
        cca.setFallbackStartingPrice(BigDecimal.ZERO);
        cca.setEpsilon(1e-5);

        SimpleRelativeNonGenericPriceUpdate<GSVMLicense> priceUpdater = new SimpleRelativeNonGenericPriceUpdate<>();
        priceUpdater.setPriceUpdate(BigDecimal.valueOf(0.1));
        priceUpdater.setInitialUpdate(BigDecimal.valueOf(0.5));
        cca.setPriceUpdater(priceUpdater);

        ProfitMaximizingNonGenericSupplementaryRound<GSVMLicense> supplementaryRound = new ProfitMaximizingNonGenericSupplementaryRound<>();
        supplementaryRound.setNumberOfSupplementaryBids(10);
        cca.addSupplementaryRound(supplementaryRound);

        SATSAllocation<GSVMLicense> allocationSR = cca.calculateAllocationAfterSupplementaryRound();

        Collection<XORBid<GSVMLicense>> bidsSR = cca.getBidsAfterSupplementaryRound();
        XORBid<GSVMLicense> bid = cca.getBidAfterSupplementaryRound(firstBidder);
        logger.info("Bids: {}", bid.getValues());
        Map<Long, BigDecimal> finalPricesMap = new HashMap<>();
        cca.getFinalPrices().forEach((key, value) -> finalPricesMap.put(key.getLongId(), value));
        logger.info("Final Prices: {}", finalPricesMap);



        logger.info("SATSAllocation: {}", allocationSR);
        logger.info("Total Declared Value: {}", allocationSR.getTotalValue());
        SATSAllocation<GSVMLicense> allocationTrueValues = allocationSR.getAllocationWithTrueValues();
        logger.info("Total True Value:     {}", allocationTrueValues.getTotalValue());
    }

    @Test
    public void testOnlySupplementaryRound() {
        Map<Long, BigDecimal> pricesPerId = new HashMap<>();
        pricesPerId.put( 0L, BigDecimal.valueOf(-1 + 20.5723888946254326390406163794986002117455));
        pricesPerId.put( 1L, BigDecimal.valueOf(-1 + 17.00197429307886994962034411528809934855));
        pricesPerId.put( 2L, BigDecimal.valueOf(-1 + 18.702171722386756944582378526816909283405));
        pricesPerId.put( 3L, BigDecimal.valueOf(-1 + 20.5723888946254326390406163794986002117455));
        pricesPerId.put( 4L, BigDecimal.valueOf(-1 + 20.5723888946254326390406163794986002117455));
        pricesPerId.put( 5L, BigDecimal.valueOf(-1 + 20.5723888946254326390406163794986002117455));
        pricesPerId.put( 6L, BigDecimal.valueOf(-1 + 20.5723888946254326390406163794986002117455));
        pricesPerId.put( 7L, BigDecimal.valueOf(-1 + 20.5723888946254326390406163794986002117455));
        pricesPerId.put( 8L, BigDecimal.valueOf(-1 + 22.62962778408797590294467801744846023292005));
        pricesPerId.put( 9L, BigDecimal.valueOf(-1 + 22.62962778408797590294467801744846023292005));
        pricesPerId.put(10L, BigDecimal.valueOf(-1 + 17.00197429307886994962034411528809934855));
        pricesPerId.put(11L, BigDecimal.valueOf(-1 + 22.62962778408797590294467801744846023292005));
        pricesPerId.put(12L, BigDecimal.valueOf(-1 + 17.00197429307886994962034411528809934855));
        pricesPerId.put(13L, BigDecimal.valueOf(-1 + 20.5723888946254326390406163794986002117455));
        pricesPerId.put(14L, BigDecimal.valueOf(-1 + 15.4563402664353363178366764684437266805));
        pricesPerId.put(15L, BigDecimal.valueOf(-1 + 17.00197429307886994962034411528809934855));
        pricesPerId.put(16L, BigDecimal.valueOf(-1 + 22.62962778408797590294467801744846023292005));
        pricesPerId.put(17L, BigDecimal.valueOf(-1 + 22.62962778408797590294467801744846023292005));

        List<GSVMBidder> rawBidders = new GlobalSynergyValueModel().createNewPopulation(123456);
        // The following line used to make a difference in the solution pool, but it does not anymore
        // new GlobalSynergyValueModel().createNewPopulation();
        List<SATSBidder<GSVMLicense>> bidders = rawBidders.stream()
                .map(b -> (SATSBidder<GSVMLicense>) b).collect(Collectors.toList());
        SATSBidder<GSVMLicense> firstBidder = bidders.get(0);
        Map<GSVMLicense, BigDecimal> prices = new HashMap<>();
        for (SATSGood good : bidders.stream().findFirst().orElseThrow(IncompatibleWorldException::new).getWorld().getLicenses()) {
            BigDecimal price = pricesPerId.get(good.getLongId());
            prices.put((GSVMLicense) good, price);
        }
        NonGenericDemandQueryMIP<GSVMLicense> demandQueryMIP = new GSVM_DemandQueryMIPBuilder().getDemandQueryMipFor(firstBidder, prices, 1e-8);
        List<NonGenericDemandQueryResult<GSVMLicense>> results = (List<NonGenericDemandQueryResult<GSVMLicense>>) demandQueryMIP.getResultPool(10);
        double sumOfValues = results.stream().mapToDouble(r -> r.getResultingBundle().value().doubleValue()).sum();
        System.out.println("Sum of values: " + sumOfValues);
    }

}
