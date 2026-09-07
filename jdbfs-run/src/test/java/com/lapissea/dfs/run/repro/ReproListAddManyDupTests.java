package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-09 list-addmany-dup
 *
 * What the code should do:
 * ContiguousIOList.addAll(Collection) (ContiguousIOList.java:494-498) delegates to
 * addMany (ContiguousIOList.java:500-568), which buffers elements in a temporary
 * in-memory batch (mem/buffIo) and flushes the batch into the list's own chunk
 * chain. When a value does not fit the list's current varying size,
 * VaryingSize.TooSmall is thrown (VaryingSize.java:364-367) and the recovery path
 * (catch at ContiguousIOList.java:540-549) should:
 *   1. flush the bytes already buffered,
 *   2. grow the varying size (growVaryingSizes, ContiguousIOList.java:370-428),
 *   3. re-add only the elements that were NOT yet flushed,
 *   4. continue with the remaining elements.
 *
 * What it actually does:
 * In the TooSmall catch, step 1 flushes the buffered bytes via flush.accept(change)
 * (ContiguousIOList.java:541-542) but never clears bufferedElements. Step 3 then calls
 * addAll(bufferedElements) (ContiguousIOList.java:546) with the list still full, so it
 * contains the elements that were just flushed in step 1 and they get written a second
 * time. bufferedElements is only cleared on the normal full-batch flush path
 * (ContiguousIOList.java:555). Result: every element buffered before the TooSmall is
 * duplicated, and deltaSize() is applied to them twice (once by the flush, once by the
 * re-add), inflating size().
 *
 * Why the test fails:
 * The element type has an int field, which ContiguousIOList forces to be varying-sized
 * (IOFieldPrimitive#maxAsFixedSize, IOFieldPrimitive.java:1553-1558); a fresh list
 * starts that field at VOID (ContiguousIOList.java:104-112). addAll([1,2,3,4,5,6,300])
 * grows VOID->BYTE on element 1 (nothing buffered yet, no dup), then elements 2..6
 * (1 byte each, NumberSize.bySizeSigned) accumulate in the batch buffer, and element
 * 300 (needs SHORT) throws TooSmall mid-batch. The 5 buffered elements are flushed,
 * the size grows to SHORT, and addAll(bufferedElements) re-adds all 6 of
 * [2,3,4,5,6,300]. The list ends up with 12 elements [1,2,3,4,5,6,2,3,4,5,6,300]
 * instead of 7, so the size() assertion below fails (actual 12, expected 7).
 */
public class ReproListAddManyDupTests{

    static{
        IOInstance.allowFullAccessI(MethodHandles.lookup());
    }

    /**
     * Element with an int field. The list forces this field to the current varying size,
     * so values drive the list's size growth VOID -> BYTE -> SHORT.
     */
    @IOValue
    public static class El extends IOInstance.Managed<El>{
        private final int v;
        public El(int v){
            this.v = v;
        }
        public int v(){
            return v;
        }
    }

    @Test
    public void addAllThatGrowsVaryingSizeMidBatchDoesNotDuplicateElements() throws IOException{
        ContiguousIOList<El> list = Cluster.emptyMem().roots().request("list", ContiguousIOList.class, El.class);

        // 1    : forces the list's int varying size VOID -> BYTE (nothing buffered yet, no dup)
        // 2..6 : fit in BYTE, accumulate in the addMany batch buffer
        // 300  : needs SHORT -> VaryingSize.TooSmall with 5 elements already buffered
        list.addAll(List.of(
                new El(1), new El(2), new El(3),
                new El(4), new El(5), new El(6), new El(300)
        ));

        // BUG: the 5 elements flushed by the TooSmall recovery are re-added by
        // addAll(bufferedElements) (ContiguousIOList.java:546) and deltaSize() is applied
        // to them twice, so size() is 12 instead of 7.
        assertThat(list.size())
            .as("addAll must store exactly the 7 elements given; the TooSmall recovery re-adds the already-flushed batch")
            .isEqualTo(7);

        var seen = new ArrayList<Integer>();
        var it = list.iterator();
        while(it.hasNext()){
            seen.add(it.ioNext().v());
        }
        // BUG: the 5 already-flushed elements (2..6) appear twice.
        assertThat(seen)
            .as("elements must not be duplicated by the TooSmall recovery path")
            .containsExactly(1, 2, 3, 4, 5, 6, 300);
    }
}
