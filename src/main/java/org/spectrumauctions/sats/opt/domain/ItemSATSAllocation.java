package org.spectrumauctions.sats.opt.domain;

import ch.uzh.ifi.ce.domain.*;
import ch.uzh.ifi.ce.mechanisms.MetaInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spectrumauctions.sats.core.model.*;

import java.math.BigDecimal;
import java.util.*;

public final class ItemSATSAllocation<T extends SATSGood> extends Allocation implements SATSAllocation<T> {

    private static final Logger logger = LogManager.getLogger(ItemSATSAllocation.class);

    private final World world;
    private final Map<SATSBidder<T>, Bundle<T>> alloc;
    private final Map<SATSBidder<T>, BigDecimal> declaredValues;

    private final BigDecimal totalValue;

    private ItemSATSAllocation(ItemAllocationBuilder<T> builder) {
        super(builder.generalizedAlloc, new Bids(), builder.metaInfo);
        this.world = builder.world;
        this.alloc = builder.alloc;
        if (builder.declaredValues == null) {
            declaredValues = new HashMap<>();
            for (Map.Entry<SATSBidder<T>, Bundle<T>> entry : alloc.entrySet()) {
                declaredValues.put(entry.getKey(), entry.getKey().calculateValue(entry.getValue()));
            }
        } else {
            this.declaredValues = builder.declaredValues;
        }
        if (builder.totalValue == null) {
            this.totalValue = BigDecimal.valueOf(this.declaredValues.values().stream().mapToDouble(BigDecimal::doubleValue).sum());
        } else {
            this.totalValue = builder.totalValue;
        }
    }

    @Override
    public Bundle<T> getAllocation(SATSBidder<T> bidder) {
        Bundle<T> candidate = alloc.get(bidder);
        if (candidate == null) {
            if (bidder.getWorld().equals(world)) {
                return new Bundle<>();
            } else {
                throw new UnequalWorldsException(
                        "BidderWorldId: " + bidder.getWorldId() + " AllocationWorldId: " + world.getId());
            }
        }
        return candidate;
    }

    @Override
    public BigDecimal getTotalValue() {
        return totalValue;
    }

    @Override
    public BigDecimal getTradeValue(SATSBidder<T> bidder) {
        return declaredValues.getOrDefault(bidder, BigDecimal.ZERO);
    }

    @Override
    public SATSAllocation<T> getAllocationWithTrueValues() {
        ItemAllocationBuilder<T> builder = new ItemAllocationBuilder<>();
        return builder
                .withAllocation(alloc)
                .withWorld(world)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ItemSATSAllocation<?> that = (ItemSATSAllocation<?>) o;

        if (world != null ? !world.equals(that.world) : that.world != null) return false;
        if (alloc != null ? !alloc.equals(that.alloc) : that.alloc != null) return false;
        if (declaredValues != null ? !declaredValues.equals(that.declaredValues) : that.declaredValues != null)
            return false;
        return getTotalValue() != null ? getTotalValue().equals(that.getTotalValue()) : that.getTotalValue() == null;
    }

    @Override
    public int hashCode() {
        int result = world != null ? world.hashCode() : 0;
        result = 31 * result + (alloc != null ? alloc.hashCode() : 0);
        result = 31 * result + (declaredValues != null ? declaredValues.hashCode() : 0);
        result = 31 * result + (getTotalValue() != null ? getTotalValue().hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        StringBuilder allocation = new StringBuilder("[");
        for (Map.Entry<SATSBidder<T>, Bundle<T>> entry : alloc.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                allocation.append(entry.getKey().getSetupType()).append(": ").append(entry.getValue().itemIds(",")).append(";    ");
            }
        }
        allocation.append("]");
        StringBuilder values = new StringBuilder("[");
        for (Map.Entry<SATSBidder<T>, BigDecimal> entry : declaredValues.entrySet()) {
            values.append(entry.getKey().getSetupType()).append(": ").append(entry.getValue()).append(";    ");
        }
        values.append("]");
        return "ItemSATSAllocation{" +
                "alloc=" + allocation.toString() +
                ", declaredValues=" + values.toString() +
                ", totalValue=" + totalValue +
                '}';
    }

    public static final class ItemAllocationBuilder<T extends SATSGood> {

        private World world;
        private Map<SATSBidder<T>, Bundle<T>> alloc;
        private Map<Bidder, BidderAllocation> generalizedAlloc;
        private BigDecimal totalValue;
        private Map<SATSBidder<T>, BigDecimal> declaredValues;
        private MetaInfo metaInfo;

        public ItemAllocationBuilder<T> withWorld(World world) {
            setWorld(world);
            return this;
        }

        public ItemAllocationBuilder<T> withAllocation(Map<SATSBidder<T>, Bundle<T>> alloc) {
            setAlloc(alloc);
            return this;
        }

        public ItemAllocationBuilder<T> withTotalValue(BigDecimal totalValue) {
            setTotalValue(totalValue);
            return this;
        }

        public ItemAllocationBuilder<T> withDeclaredValues(Map<SATSBidder<T>, BigDecimal> declaredValues) {
            setDeclaredValues(declaredValues);
            return this;
        }

        public ItemAllocationBuilder<T> withMetaInfo(MetaInfo metaInfo) {
            setMetaInfo(metaInfo);
            return this;
        }


        public ItemSATSAllocation<T> build() {
            return new ItemSATSAllocation<>(this);
        }

        private void setWorld(World world) {
            this.world = world;
        }

        private void setAlloc(Map<SATSBidder<T>, Bundle<T>> alloc) {
            this.alloc = alloc;
            this.generalizedAlloc = new HashMap<>();
            Map<Good, Integer> quantities = new HashMap<>();
            for (Map.Entry<SATSBidder<T>, Bundle<T>> entry : alloc.entrySet()) {
                entry.getValue().forEach(g -> quantities.put(g, 1));
                BidderAllocation bidderAllocation = new BidderAllocation(entry.getKey().calculateValue(entry.getValue()), quantities, Collections.emptySet());
                generalizedAlloc.put(entry.getKey(), bidderAllocation);
            }
        }

        private void setTotalValue(BigDecimal totalValue) {
            this.totalValue = totalValue;
        }

        public void setDeclaredValues(Map<SATSBidder<T>, BigDecimal> declaredValues) {
            this.declaredValues = declaredValues;
        }

        public void setMetaInfo(MetaInfo metaInfo) {
            this.metaInfo = metaInfo;
        }
    }
}