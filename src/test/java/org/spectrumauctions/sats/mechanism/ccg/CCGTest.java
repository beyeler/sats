package org.spectrumauctions.sats.mechanism.ccg;

import com.google.common.collect.Sets;
import edu.harvard.econcs.jopt.solver.mip.MIP;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.spectrumauctions.sats.core.bidlang.generic.GenericBid;
import org.spectrumauctions.sats.core.bidlang.generic.GenericDefinition;
import org.spectrumauctions.sats.core.bidlang.xor.XORBid;
import org.spectrumauctions.sats.core.model.LicenseBundle;
import org.spectrumauctions.sats.core.model.mrvm.MRVMBidder;
import org.spectrumauctions.sats.core.model.mrvm.MRVMLicense;
import org.spectrumauctions.sats.core.model.mrvm.MultiRegionModel;
import org.spectrumauctions.sats.mechanism.MockWorld;
import org.spectrumauctions.sats.mechanism.domain.Payment;
import org.spectrumauctions.sats.mechanism.domain.mechanisms.AuctionMechanism;
import org.spectrumauctions.sats.opt.domain.WinnerDeterminator;
import org.spectrumauctions.sats.opt.model.mrvm.MRVM_MIP;
import org.spectrumauctions.sats.opt.xor.XORWinnerDetermination;
import org.spectrumauctions.sats.opt.xorq.XORQWinnerDetermination;

import java.util.*;

import static org.junit.Assert.assertEquals;

public class CCGTest {

    private MockWorld.MockGood A;
    private MockWorld.MockGood B;
    private MockWorld.MockGood C;
    private MockWorld.MockGood D;
    private MockWorld.MockGood E;

    private MockWorld.MockBand B0;
    private MockWorld.MockBand B1;
    private MockWorld.MockBand B2;

    private Map<Integer, MockWorld.MockBidder> bidders;

    @Before
    public void setUp() {
        A = MockWorld.getInstance().createNewGood();
        B = MockWorld.getInstance().createNewGood();
        B0 = MockWorld.getInstance().createNewBand(Sets.newHashSet(A, B));
        C = MockWorld.getInstance().createNewGood();
        D = MockWorld.getInstance().createNewGood();
        B1 = MockWorld.getInstance().createNewBand(Sets.newHashSet(C, D));
        E = MockWorld.getInstance().createNewGood();
        B2 = MockWorld.getInstance().createNewBand(Sets.newHashSet(E));
        bidders = new HashMap<>();
        MockWorld.getInstance().reset();
    }


    private MockWorld.MockBidder bidder(int id) {
        MockWorld.MockBidder fromMap = bidders.get(id);
        if (fromMap == null) {
            MockWorld.MockBidder bidder = MockWorld.getInstance().createNewBidder();
            bidders.put((int) bidder.getLongId(), bidder);
            return bidder(id);
        }
        return fromMap;
    }

    @Test
    public void testSimpleCCG() {
        bidder(1).addBid(new LicenseBundle<>(A), 2);
        bidder(2).addBid(new LicenseBundle<>(B), 2);
        bidder(3).addBid(new LicenseBundle<>(A, B), 2);

        Set<XORBid<MockWorld.MockGood>> xorBids = new HashSet<>();
        xorBids.add(new XORBid.Builder<>(bidder(1), bidder(1).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(2), bidder(2).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(3), bidder(3).getValues()).build());

        WinnerDeterminator<MockWorld.MockGood> wdp = new XORWinnerDetermination<>(xorBids);
        AuctionMechanism<MockWorld.MockGood> am = new CCGMechanism<>(wdp);
        Payment<MockWorld.MockGood> payment = am.getPayment();
        assertEquals(am.getMechanismResult().getAllocation().getTotalValue().doubleValue(), 4, 0.0001);
        assertEquals(payment.paymentOf(bidder(1)).getAmount(), 1, 0.00001);
        assertEquals(payment.paymentOf(bidder(2)).getAmount(), 1, 0.00001);
        assertEquals(payment.paymentOf(bidder(3)).getAmount(), 0, 0.00001);
    }

    @Test
    public void testScalingOfSimpleCCG() {
        bidder(1).addBid(new LicenseBundle<>(A), MIP.MAX_VALUE * 2);
        bidder(2).addBid(new LicenseBundle<>(B), MIP.MAX_VALUE * 2);
        bidder(3).addBid(new LicenseBundle<>(A, B), MIP.MAX_VALUE * 2);

        Set<XORBid<MockWorld.MockGood>> xorBids = new HashSet<>();
        xorBids.add(new XORBid.Builder<>(bidder(1), bidder(1).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(2), bidder(2).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(3), bidder(3).getValues()).build());

        WinnerDeterminator<MockWorld.MockGood> wdp = new XORWinnerDetermination<>(xorBids);
        AuctionMechanism<MockWorld.MockGood> am = new CCGMechanism<>(wdp);
        Payment<MockWorld.MockGood> payment = am.getPayment();
        assertEquals(am.getMechanismResult().getAllocation().getTotalValue().doubleValue(), MIP.MAX_VALUE * 4, 1e-1);
        assertEquals(payment.paymentOf(bidder(1)).getAmount(), MIP.MAX_VALUE, 1e-1);
        assertEquals(payment.paymentOf(bidder(2)).getAmount(), MIP.MAX_VALUE, 1e-1);
        assertEquals(payment.paymentOf(bidder(3)).getAmount(), 0, 1e-1);
    }

    @Test
    public void testSimpleCCGWithTraitor() {
        bidder(1).addBid(new LicenseBundle<>(A), 10);
        bidder(2).addBid(new LicenseBundle<>(B), 10);
        bidder(3).addBid(new LicenseBundle<>(C), 10);
        bidder(4).addBid(new LicenseBundle<>(A, B), 6);

        Set<XORBid<MockWorld.MockGood>> xorBids = new HashSet<>();
        xorBids.add(new XORBid.Builder<>(bidder(1), bidder(1).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(2), bidder(2).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(3), bidder(3).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(4), bidder(4).getValues()).build());

        WinnerDeterminator<MockWorld.MockGood> wdp = new XORWinnerDetermination<>(xorBids);
        AuctionMechanism<MockWorld.MockGood> am = new CCGMechanism<>(wdp);
        Payment<MockWorld.MockGood> payment = am.getPayment();
        assertEquals(am.getMechanismResult().getAllocation().getTotalValue().doubleValue(), 30, 0.0001);
        assertEquals(payment.paymentOf(bidder(1)).getAmount(), 3, 0.00001);
        assertEquals(payment.paymentOf(bidder(2)).getAmount(), 3, 0.00001);
        assertEquals(payment.paymentOf(bidder(3)).getAmount(), 0, 0.00001);
        assertEquals(payment.paymentOf(bidder(4)).getAmount(), 0, 0.00001);
    }

    @Test
    public void testCCGWithTraitor() {
        bidder(1).addBid(new LicenseBundle<>(A), 10);
        bidder(2).addBid(new LicenseBundle<>(B), 10);
        bidder(3).addBid(new LicenseBundle<>(C), 10);
        bidder(4).addBid(new LicenseBundle<>(D), 10);
        bidder(5).addBid(new LicenseBundle<>(A, B, C, D, E), 12);
        bidder(6).addBid(new LicenseBundle<>(A, B, E), 8);
        bidder(7).addBid(new LicenseBundle<>(C, D, E), 8);

        Set<XORBid<MockWorld.MockGood>> xorBids = new HashSet<>();
        xorBids.add(new XORBid.Builder<>(bidder(1), bidder(1).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(2), bidder(2).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(3), bidder(3).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(4), bidder(4).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(5), bidder(5).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(6), bidder(6).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(7), bidder(7).getValues()).build());

        WinnerDeterminator<MockWorld.MockGood> wdp = new XORWinnerDetermination<>(xorBids);
        AuctionMechanism<MockWorld.MockGood> am = new CCGMechanism<>(wdp);
        Payment<MockWorld.MockGood> payment = am.getPayment();
        assertEquals(am.getMechanismResult().getAllocation().getTotalValue().doubleValue(), 40, 0.0001);
        assertEquals(payment.paymentOf(bidder(1)).getAmount(), 4, 0.00001);
        assertEquals(payment.paymentOf(bidder(2)).getAmount(), 4, 0.00001);
        assertEquals(payment.paymentOf(bidder(3)).getAmount(), 4, 0.00001);
        assertEquals(payment.paymentOf(bidder(4)).getAmount(), 4, 0.00001);
        assertEquals(payment.paymentOf(bidder(5)).getAmount(), 0, 0.00001);
        assertEquals(payment.paymentOf(bidder(6)).getAmount(), 0, 0.00001);
        assertEquals(payment.paymentOf(bidder(7)).getAmount(), 0, 0.00001);
    }

    @Test
    public void testScalingOfCCGWithTraitor() {
        bidder(1).addBid(new LicenseBundle<>(A), MIP.MAX_VALUE * 0.5);
        bidder(2).addBid(new LicenseBundle<>(B), MIP.MAX_VALUE * 0.5);
        bidder(3).addBid(new LicenseBundle<>(C), MIP.MAX_VALUE * 0.5);
        bidder(4).addBid(new LicenseBundle<>(D), MIP.MAX_VALUE * 0.5);
        bidder(5).addBid(new LicenseBundle<>(A, B, C, D, E), MIP.MAX_VALUE * 0.6);
        bidder(6).addBid(new LicenseBundle<>(A, B, E), MIP.MAX_VALUE * 0.4);
        bidder(7).addBid(new LicenseBundle<>(C, D, E), MIP.MAX_VALUE * 0.4);

        Set<XORBid<MockWorld.MockGood>> xorBids = new HashSet<>();
        xorBids.add(new XORBid.Builder<>(bidder(1), bidder(1).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(2), bidder(2).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(3), bidder(3).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(4), bidder(4).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(5), bidder(5).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(6), bidder(6).getValues()).build());
        xorBids.add(new XORBid.Builder<>(bidder(7), bidder(7).getValues()).build());

        WinnerDeterminator<MockWorld.MockGood> wdp = new XORWinnerDetermination<>(xorBids);
        AuctionMechanism<MockWorld.MockGood> am = new CCGMechanism<>(wdp);
        Payment<MockWorld.MockGood> payment = am.getPayment();
        assertEquals(am.getMechanismResult().getAllocation().getTotalValue().doubleValue(), MIP.MAX_VALUE * 2, 1e-1);
        assertEquals(payment.paymentOf(bidder(1)).getAmount(), MIP.MAX_VALUE * 0.2, 1e-1);
        assertEquals(payment.paymentOf(bidder(2)).getAmount(), MIP.MAX_VALUE * 0.2, 1e-1);
        assertEquals(payment.paymentOf(bidder(3)).getAmount(), MIP.MAX_VALUE * 0.2, 1e-1);
        assertEquals(payment.paymentOf(bidder(4)).getAmount(), MIP.MAX_VALUE * 0.2, 1e-1);
        assertEquals(payment.paymentOf(bidder(5)).getAmount(), 0, 1e-1);
        assertEquals(payment.paymentOf(bidder(6)).getAmount(), 0, 1e-1);
        assertEquals(payment.paymentOf(bidder(7)).getAmount(), 0, 1e-1);
    }

    @Test
    public void testCCGWithGenericGoods() {
        Map<MockWorld.MockBand, Integer> bid1 = new HashMap<>();
        bid1.put(B0, 1);
        bid1.put(B1, 1);
        bidder(1).addGenericBid(bid1, 2);

        Map<MockWorld.MockBand, Integer> bid2 = new HashMap<>();
        bid2.put(B0, 1);
        bid2.put(B1, 1);
        bid2.put(B2, 1);
        bidder(2).addGenericBid(bid2, 3);

        Map<MockWorld.MockBand, Integer> bid3 = new HashMap<>();
        bid3.put(B1, 1);
        bidder(3).addGenericBid(bid3, 1.5);

        Map<MockWorld.MockBand, Integer> bid4 = new HashMap<>();
        bid4.put(B0, 2);
        bid4.put(B1, 2);
        bid4.put(B2, 1);
        bidder(4).addGenericBid(bid4, 4);

        Set<GenericBid<GenericDefinition<MockWorld.MockGood>, MockWorld.MockGood>> bids = new HashSet<>();
        bids.add(new GenericBid<>(bidder(1), bidder(1).getGenericBids()));
        bids.add(new GenericBid<>(bidder(2), bidder(2).getGenericBids()));
        bids.add(new GenericBid<>(bidder(3), bidder(3).getGenericBids()));
        bids.add(new GenericBid<>(bidder(4), bidder(4).getGenericBids()));

        WinnerDeterminator<MockWorld.MockGood> wdp = new XORQWinnerDetermination<>(bids);
        AuctionMechanism<MockWorld.MockGood> am = new CCGMechanism<>(wdp);
        Payment<MockWorld.MockGood> payment = am.getPayment();
        assertEquals(4, payment.getTotalPayments(), 1e-6);
        assertEquals(1.75, payment.paymentOf(bidder(1)).getAmount(), 1e-6);
        assertEquals(2.25, payment.paymentOf(bidder(2)).getAmount(), 1e-6);
        assertEquals(0, payment.paymentOf(bidder(3)).getAmount(), 1e-6);
        assertEquals(0, payment.paymentOf(bidder(4)).getAmount(), 1e-6);
    }

    @Test
    public void testCCGWithScalingAndGenericGoods() {
        Map<MockWorld.MockBand, Integer> bid1 = new HashMap<>();
        bid1.put(B0, 1);
        bid1.put(B1, 1);
        bidder(1).addGenericBid(bid1, MIP.MAX_VALUE * 2);

        Map<MockWorld.MockBand, Integer> bid2 = new HashMap<>();
        bid2.put(B0, 1);
        bid2.put(B1, 1);
        bid2.put(B2, 1);
        bidder(2).addGenericBid(bid2, MIP.MAX_VALUE * 3);

        Map<MockWorld.MockBand, Integer> bid3 = new HashMap<>();
        bid3.put(B1, 1);
        bidder(3).addGenericBid(bid3, MIP.MAX_VALUE * 1.5);

        Map<MockWorld.MockBand, Integer> bid4 = new HashMap<>();
        bid4.put(B0, 2);
        bid4.put(B1, 2);
        bid4.put(B2, 1);
        bidder(4).addGenericBid(bid4, MIP.MAX_VALUE * 4);

        Set<GenericBid<GenericDefinition<MockWorld.MockGood>, MockWorld.MockGood>> bids = new HashSet<>();
        bids.add(new GenericBid<>(bidder(1), bidder(1).getGenericBids()));
        bids.add(new GenericBid<>(bidder(2), bidder(2).getGenericBids()));
        bids.add(new GenericBid<>(bidder(3), bidder(3).getGenericBids()));
        bids.add(new GenericBid<>(bidder(4), bidder(4).getGenericBids()));

        WinnerDeterminator<MockWorld.MockGood> wdp = new XORQWinnerDetermination<>(bids);
        AuctionMechanism<MockWorld.MockGood> am = new CCGMechanism<>(wdp);
        Payment<MockWorld.MockGood> payment = am.getPayment();
        assertEquals(MIP.MAX_VALUE * 4, payment.getTotalPayments(), 1);
        assertEquals(MIP.MAX_VALUE * 1.75, payment.paymentOf(bidder(1)).getAmount(), 1);
        assertEquals(MIP.MAX_VALUE * 2.25, payment.paymentOf(bidder(2)).getAmount(), 1);
        assertEquals(0, payment.paymentOf(bidder(3)).getAmount(), 1);
        assertEquals(0, payment.paymentOf(bidder(4)).getAmount(), 1);
    }

    @Test
    @Ignore // Takes a long time
    public void testCCGWithStandardMRVM() {
        List<MRVMBidder> bidders = new MultiRegionModel().createNewPopulation(234456867);
        WinnerDeterminator<MRVMLicense> wdp = new MRVM_MIP(bidders);
        AuctionMechanism<MRVMLicense> am = new CCGMechanism<>(wdp);
        Payment<MRVMLicense> payment = am.getPayment();
        assertEquals(1.2262127287524E10, am.getMechanismResult().getAllocation().getTotalValue().doubleValue(), 1e-2);
    }

}
