package org.spectrumauctions.sats.mechanism.cca.priceupdate;

import org.spectrumauctions.sats.core.bidlang.generic.GenericDefinition;
import org.spectrumauctions.sats.core.model.SATSGood;

import java.math.BigDecimal;
import java.util.Map;

public interface GenericPriceUpdater<G extends GenericDefinition<S>, S extends SATSGood> {
    Map<G, BigDecimal> updatePrices(Map<G, BigDecimal> oldPrices, Map<G, Integer> demand);
    Map<G, BigDecimal> getLastPrices();
}
