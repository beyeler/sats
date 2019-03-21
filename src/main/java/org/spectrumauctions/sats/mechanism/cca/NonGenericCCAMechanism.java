package org.spectrumauctions.sats.mechanism.cca;

import com.google.common.base.Preconditions;
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spectrumauctions.sats.core.bidlang.xor.SizeBasedUniqueRandomXOR;
import org.spectrumauctions.sats.core.bidlang.xor.XORBid;
import org.spectrumauctions.sats.core.bidlang.xor.XORValue;
import org.spectrumauctions.sats.core.model.*;
import org.spectrumauctions.sats.core.util.random.JavaUtilRNGSupplier;
import org.spectrumauctions.sats.core.util.random.RNGSupplier;
import org.spectrumauctions.sats.mechanism.cca.priceupdate.NonGenericPriceUpdater;
import org.spectrumauctions.sats.mechanism.cca.priceupdate.SimpleRelativeNonGenericPriceUpdate;
import org.spectrumauctions.sats.mechanism.cca.supplementaryround.NonGenericSupplementaryRound;
import org.spectrumauctions.sats.mechanism.cca.supplementaryround.ProfitMaximizingNonGenericSupplementaryRound;
import org.spectrumauctions.sats.mechanism.ccg.CCGMechanism;
import org.spectrumauctions.sats.mechanism.domain.MechanismResult;
import org.spectrumauctions.sats.mechanism.domain.mechanisms.AuctionMechanism;
import org.spectrumauctions.sats.mechanism.vcg.VCGMechanism;
import org.spectrumauctions.sats.opt.domain.SATSAllocation;
import org.spectrumauctions.sats.opt.domain.NonGenericDemandQueryMIP;
import org.spectrumauctions.sats.opt.domain.NonGenericDemandQueryMIPBuilder;
import org.spectrumauctions.sats.opt.domain.NonGenericDemandQueryResult;
import org.spectrumauctions.sats.opt.xor.XORWinnerDetermination;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

public class NonGenericCCAMechanism<T extends SATSGood> extends CCAMechanism<T> {

    private static final Logger logger = LogManager.getLogger(NonGenericCCAMechanism.class);

    private NonGenericDemandQueryMIPBuilder<T> demandQueryMIPBuilder;
    private Map<SATSGood, BigDecimal> startingPrices = new HashMap<>();
    private NonGenericPriceUpdater<T> priceUpdater = new SimpleRelativeNonGenericPriceUpdate<>();
    private List<NonGenericSupplementaryRound<T>> supplementaryRounds = new ArrayList<>();

    private Collection<XORBid<T>> bidsAfterClockPhase;
    private Collection<XORBid<T>> bidsAfterSupplementaryRound;

    private Map<T, BigDecimal> finalPrices;
    private Map<T, Integer> finalDemand;

    public NonGenericCCAMechanism(List<SATSBidder<T>> bidders, NonGenericDemandQueryMIPBuilder<T> nonGenericDemandQueryMIPBuilder) {
        super(bidders);
        this.demandQueryMIPBuilder = nonGenericDemandQueryMIPBuilder;
    }

    @Override
    public MechanismResult<T> getMechanismResult() {
        if (result != null) return result;
        if (bidsAfterClockPhase == null) {
            logger.info("Starting clock phase for XOR bids...");
            bidsAfterClockPhase = runClockPhase();
        }
        if (bidsAfterSupplementaryRound == null) {
            logger.info("Starting to collect bids for supplementary round...");
            bidsAfterSupplementaryRound = runSupplementaryRound();
        }
        logger.info("Starting to calculate payments with all collected bids...");
        result = calculatePayments();
        return result;
    }

    public void setStartingPrice(SATSGood good, BigDecimal price) {
        startingPrices.put(good, price);
    }

    @Override
    public void calculateSampledStartingPrices(int bidsPerBidder, int numberOfWorldSamples, double fraction, long seed) {
        World world = bidders.stream().findAny().map(SATSBidder::getWorld).orElseThrow(NoSuchFieldError::new);
        // We need a fixed order -> List
        List<SATSGood> licenseList = new ArrayList<>(world.getLicenses());
        try {
            ArrayList<Double> yVector = new ArrayList<>();
            ArrayList<ArrayList<Double>> xVectors = new ArrayList<>();

            RNGSupplier rngSupplier = new JavaUtilRNGSupplier(seed);
            for (int i = 0; i < numberOfWorldSamples; i++) {
                List<SATSBidder<T>> alternateBidders = bidders.stream().map(b -> b.drawSimilarBidder(rngSupplier)).collect(Collectors.toList());
                for (SATSBidder<T> bidder : alternateBidders) {
                    SizeBasedUniqueRandomXOR valueFunction;

                    valueFunction = bidder.getValueFunction(SizeBasedUniqueRandomXOR.class, rngSupplier);
                    valueFunction.setIterations(bidsPerBidder);

                    Iterator<XORValue<T>> bidIterator = valueFunction.iterator();
                    while (bidIterator.hasNext()) {
                        XORValue<T> bid = bidIterator.next();
                        yVector.add(bid.value().doubleValue());
                        ArrayList<Double> xVector = new ArrayList<>();
                        for (SATSGood license : licenseList) {
                            double value = 0;
                            if (bid.getLicenses().contains(license)) {
                                value = 1;
                            }
                            xVector.add(value);
                        }
                        xVectors.add(xVector);
                    }
                }
            }


            OLSMultipleLinearRegression regression = new OLSMultipleLinearRegression();
            regression.setNoIntercept(true);
            double[] yArray = new double[yVector.size()];
            for (int i = 0; i < yVector.size(); i++) {
                yArray[i] = yVector.get(i);
            }
            double[][] xArrays = new double[xVectors.size()][xVectors.get(0).size()];
            for (int i = 0; i < xVectors.size(); i++) {
                for (int j = 0; j < xVectors.get(i).size(); j++) {
                    xArrays[i][j] = xVectors.get(i).get(j);
                }
            }

            regression.newSampleData(yArray, xArrays);
            double[] betas = regression.estimateRegressionParameters();

            for (int i = 0; i < licenseList.size(); i++) {
                double prediction = Math.max(betas[i], 0.0);
                double price = prediction * fraction;
                logger.info("{}:\nFound prediction of {}, setting starting price to {}.",
                        licenseList.get(i), prediction, price);
                setStartingPrice(licenseList.get(i), BigDecimal.valueOf(price));
            }

        } catch (UnsupportedBiddingLanguageException e) {
            // Catching this error here, because it's very unlikely to happen and we don't want to bother
            // the user with handling this error. We just log it and don't set the starting prices.
            logger.error("Tried to calculate sampled starting prices, but {} doesn't support the " +
                    "SizeBasedUniqueRandomXOR bidding language. Not setting any starting prices.", world);
        }
    }

    public SATSAllocation<T> calculateClockPhaseAllocation() {
        if (bidsAfterClockPhase == null) {
            logger.info("Starting clock phase for XOR bids...");
            bidsAfterClockPhase = runClockPhase();
        }
        Set<XORBid<T>> bids = new HashSet<>(bidsAfterClockPhase);

        XORWinnerDetermination<T> wdp = new XORWinnerDetermination<>(bids);
        return wdp.calculateAllocation();
    }

    public SATSAllocation<T> calculateAllocationAfterSupplementaryRound() {
        if (bidsAfterClockPhase == null) {
            logger.info("Starting clock phase for XOR bids...");
            bidsAfterClockPhase = runClockPhase();
        }
        if (bidsAfterSupplementaryRound == null) {
            logger.info("Starting to collect bids for supplementary round...");
            bidsAfterSupplementaryRound = runSupplementaryRound();
        }
        Set<XORBid<T>> bids = new HashSet<>(bidsAfterSupplementaryRound);

        XORWinnerDetermination<T> wdp = new XORWinnerDetermination<>(bids);
        return wdp.calculateAllocation();
    }

    private Collection<XORBid<T>> runClockPhase() {
        Map<SATSBidder<T>, XORBid<T>> bids = new HashMap<>();
        bidders.forEach(bidder -> bids.put(bidder, new XORBid.Builder<>(bidder).build()));
        Map<T, BigDecimal> prices = new HashMap<>();
        for (SATSGood good : bidders.stream().findFirst().orElseThrow(IncompatibleWorldException::new).getWorld().getLicenses()) {
            prices.put((T) good, startingPrices.getOrDefault(good, fallbackStartingPrice));
        }

        Map<T, Integer> demand;
        boolean done = false;
        while (!done) {
            Map<T, BigDecimal> currentPrices = prices; // For lambda use
            demand = new HashMap<>();

            for (SATSBidder<T> bidder : bidders) {
                NonGenericDemandQueryMIP<T> demandQueryMIP = demandQueryMIPBuilder.getDemandQueryMipFor(bidder, prices, epsilon);
                demandQueryMIP.setTimeLimit(getTimeLimit());
                List<? extends NonGenericDemandQueryResult<T>> demandQueryResults = demandQueryMIP.getResultPool(clockPhaseNumberOfBundles);
                Bundle<T> firstBundle = demandQueryResults.get(0).getResultingBundle().getLicenses();
                if (firstBundle.size() > 0) {
                    for (T good : firstBundle) {
                        demand.put(good, demand.getOrDefault(good, 0) + 1);
                    }
                }
                for (NonGenericDemandQueryResult<T> demandQueryResult : demandQueryResults) {
                    if (demandQueryResult.getResultingBundle().getLicenses().size() > 0) {
                        Bundle<T> bundle = demandQueryResult.getResultingBundle().getLicenses();

                        XORBid.Builder<T> xorBidBuilder = new XORBid.Builder<>(bidder, bids.get(bidder).getValues());
                        BigDecimal bid = BigDecimal.valueOf(bundle.stream().mapToDouble(l -> currentPrices.get(l).doubleValue()).sum());
                        XORValue<T> existing = xorBidBuilder.containsBundle(bundle);
                        if (existing != null && existing.value().compareTo(bid) < 1) {
                            xorBidBuilder.removeFromBid(existing);
                        }
                        if (existing == null || existing.value().compareTo(bid) < 0) {
                            xorBidBuilder.add(new XORValue<>(bundle, bid));
                        }

                        XORBid<T> newBid = xorBidBuilder.build();
                        bids.put(bidder, newBid);
                    }
                }
            }
            Map<T, BigDecimal> updatedPrices = priceUpdater.updatePrices(prices, demand);
            if (prices.equals(updatedPrices) || totalRounds >= maxRounds) {
                done = true;
                finalDemand = demand;
                finalPrices = prices;
            } else {
                prices = updatedPrices;
                totalRounds++;
            }
        }
        bidsAfterClockPhase = bids.values();
        return bidsAfterClockPhase;
    }

    private Collection<XORBid<T>> runSupplementaryRound() {
        Collection<XORBid<T>> bids = new HashSet<>();
        if (supplementaryRounds.isEmpty())
            supplementaryRounds.add(new ProfitMaximizingNonGenericSupplementaryRound<>());

        for (SATSBidder<T> bidder : bidders) {
            List<XORValue<T>> newValues = new ArrayList<>();
            for (NonGenericSupplementaryRound<T> supplementaryRound : supplementaryRounds) {
                newValues.addAll(supplementaryRound.getSupplementaryBids(this, bidder));
            }


            XORBid<T> bidderBid = bidsAfterClockPhase.stream().filter(bid -> bidder.equals(bid.getBidder())).findFirst().orElseThrow(NoSuchElementException::new);

            XORBid<T> newBid = bidderBid.copyOfWithNewValues(newValues);
            bids.add(newBid);
        }
        bidsAfterSupplementaryRound = bids;
        return bids;
    }

    private MechanismResult<T> calculatePayments() {
        Set<XORBid<T>> bids = new HashSet<>(bidsAfterSupplementaryRound);
        XORWinnerDetermination<T> wdp = new XORWinnerDetermination<>(bids);
        AuctionMechanism<T> mechanism;
        switch (paymentRule) {
            case CCG:
                mechanism = new CCGMechanism<>(wdp);
                break;
            case VCG:
            default:
                mechanism = new VCGMechanism<>(wdp);
                break;
        }
        result = mechanism.getMechanismResult();
        return result;
    }

    public int getSupplyMinusDemand() {
        World world = bidders.iterator().next().getWorld();
        Set<T> licenses = (Set<T>) world.getLicenses();
        int aggregateDemand = 0;
        int supply = 0;
        for (T def : licenses) {
            aggregateDemand += finalDemand.getOrDefault(def, 0);
            supply++;
        }
        return supply - aggregateDemand;
    }

    public Collection<XORBid<T>> getBidsAfterClockPhase() {
        if (bidsAfterClockPhase == null) {
            runClockPhase();
        }
        return bidsAfterClockPhase;
    }

    public Collection<XORBid<T>> getBidsAfterSupplementaryRound() {
        if (bidsAfterClockPhase == null) {
            runClockPhase();
        }
        if (bidsAfterSupplementaryRound == null) {
            runSupplementaryRound();
        }
        return bidsAfterSupplementaryRound;
    }

    public Map<SATSBidder<T>, Integer> getXORBidsCount() {
        Map<SATSBidder<T>, Integer> map = new HashMap<>();
        bidsAfterClockPhase.forEach(bid -> map.put(bid.getBidder(), bid.getValues().size()));
        return map;
    }

    public void setPriceUpdater(NonGenericPriceUpdater<T> nonGenericPriceUpdater) {
        Preconditions.checkArgument(bidsAfterClockPhase == null, "Already ran clock phase! Set the price updater before.");
        this.priceUpdater = nonGenericPriceUpdater;
    }

    public void addSupplementaryRound(NonGenericSupplementaryRound<T> nonGenericSupplementaryRound) {
        Preconditions.checkArgument(bidsAfterSupplementaryRound == null, "Already ran supplementary round!");
        this.supplementaryRounds.add(nonGenericSupplementaryRound);
    }

    public Map<SATSBidder<T>, Integer> getBidCountAfterClockPhase() {
        Map<SATSBidder<T>, Integer> map = new HashMap<>();
        bidsAfterClockPhase.forEach(bid -> map.put(bid.getBidder(), bid.getValues().size()));
        return map;
    }

    public Map<SATSBidder<T>, Integer> getBidCountAfterSupplementaryRound() {
        Map<SATSBidder<T>, Integer> map = new HashMap<>();
        bidsAfterSupplementaryRound.forEach(bid -> map.put(bid.getBidder(), bid.getValues().size()));
        return map;
    }

    public NonGenericDemandQueryMIPBuilder<T> getDemandQueryBuilder() {
        return this.demandQueryMIPBuilder;
    }

    public Map<T, BigDecimal> getFinalPrices() {
        return finalPrices;
    }

    public Map<T, BigDecimal> getLastPrices() {
        return priceUpdater.getLastPrices();
    }

    public XORBid<T> getBidAfterClockPhase(SATSBidder<T> bidder) {
        for (XORBid<T> bid : bidsAfterClockPhase) {
            if (bid.getBidder().equals(bidder)) return bid;
        }
        logger.warn("Couldn't find a bid for bidder {} after clock phase.", bidder);
        return null;
    }


    public XORBid<T> getBidAfterSupplementaryRound(SATSBidder<T> bidder) {
        for (XORBid<T> bid : bidsAfterSupplementaryRound) {
            if (bid.getBidder().equals(bidder)) return bid;
        }
        logger.warn("Couldn't find a bid for bidder {} after supplementary round.", bidder);
        return null;
    }

}
