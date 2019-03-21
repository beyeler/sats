/**
 * Copyright by Michael Weiss, weiss.michael@gmx.ch
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.spectrumauctions.sats.opt.model.mrvm;

import edu.harvard.econcs.jopt.solver.IMIPResult;
import org.spectrumauctions.sats.core.bidlang.generic.GenericValue;
import org.spectrumauctions.sats.core.model.SATSBidder;
import org.spectrumauctions.sats.core.model.mrvm.*;
import org.spectrumauctions.sats.core.model.mrvm.MRVMRegionsMap.Region;
import org.spectrumauctions.sats.opt.domain.GenericSATSAllocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;

/**
 * @author Michael Weiss
 *
 */
public final class MRVMMipResult extends GenericSATSAllocation<MRVMGenericDefinition, MRVMLicense> {

    private final MRVMWorld world;

    private final IMIPResult joptResult;

    private MRVMMipResult(Builder builder) {
        super(builder);
        this.world = builder.world;
        this.joptResult = builder.joptResult;
    }

    public IMIPResult getJoptResult() {
        return joptResult;
    }


    public MRVMWorld getWorld() {
        return world;
    }

    public String toString() {
        String tab = "\t";
        StringBuilder builder = new StringBuilder();

        List<Entry<SATSBidder<MRVMLicense>, GenericValue<MRVMGenericDefinition, MRVMLicense>>> sortedEntries = new ArrayList<>(values.entrySet());
        Collections.sort(sortedEntries, Comparator.comparing(e -> ((Long) e.getKey().getLongId())));


        builder.append("===== bidder listing =======").append(System.lineSeparator());
        for (Entry<SATSBidder<MRVMLicense>, GenericValue<MRVMGenericDefinition, MRVMLicense>> entry : sortedEntries) {
            MRVMBidder bidder = (MRVMBidder) entry.getKey();

            builder.append(entry.getKey().getLongId())
                    .append(tab)
                    .append(entry.getKey().getClass().getSimpleName())
                    .append("(")
                    .append(bidder.getSetupType())
                    .append(")")
                    .append(tab)
                    .append(entry.getValue().getValue().toString())
                    .append(" #licenses:")
                    .append(entry.getValue().getTotalQuantity())
                    .append(System.lineSeparator());
        }
        builder.append("===== allocation table =======").append(System.lineSeparator());

        if (!values.isEmpty()) {
            MRVMWorld world = (MRVMWorld) values.keySet().iterator().next().getWorld();
            List<MRVMBand> orderedBands = new ArrayList<>(world.getBands());
            //Order bands by increasing name length
            Collections.sort(orderedBands, (b1, b2) -> ((Integer) b1.getName().length()).compareTo((Integer) b2.getName().length()));
            for (MRVMBand band : orderedBands) {
                builder.append(tab).append(band.getName());
            }
            builder.append(System.lineSeparator());
            for (Region region : world.getRegionsMap().getRegions()) {
                //Print region information
                builder.append("rid")
                        .append(region.getId())
                        .append(tab)
                        .append("(")
                        .append(region.getNote())
                        .append(", population: ")
                        .append(region.getPopulation())
                        .append(")")
                        .append(System.lineSeparator());
                //Print allocation in reguin
                for (Entry<SATSBidder<MRVMLicense>, GenericValue<MRVMGenericDefinition, MRVMLicense>> entry : sortedEntries) {
                    builder.append(tab);
                    for (MRVMBand band : orderedBands) {
                        MRVMGenericDefinition def = new MRVMGenericDefinition(band, region);
                        int quantity = entry.getValue().getQuantity(def);
//                        builder.append(entry.getKey().getLongId())
//                        .append(":")
                        builder.append(quantity)
                                .append(tab)
                                .append(tab)
                                .append(tab);
                    }
                    MRVMBidder bidder = (MRVMBidder) entry.getKey();
                    builder.append(entry.getKey().getClass().getSimpleName())
                            .append(entry.getKey().getLongId())
                            .append(" (")
                            .append(bidder.getSetupType())
                            .append(")");
                    builder.append(System.lineSeparator());
                }
                builder.append("----------------newregion----------------")
                        .append(System.lineSeparator());
            }
        }
        return builder.toString();
    }

    public static final class Builder extends GenericSATSAllocation.Builder<MRVMGenericDefinition, MRVMLicense> {


        private MRVMWorld world;
        private final IMIPResult joptResult;

        /**
         *
         * @param world
         * @param joptResult The result object //TODO Use Result object here in construction to build MRVMMipResult
         */
        public Builder(MRVMWorld world, IMIPResult joptResult) {
            super();
            this.world = world;
            this.joptResult = joptResult;
        }

        public MRVMMipResult build() {
            return new MRVMMipResult(this);
        }
    }

}
