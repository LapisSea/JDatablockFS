package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.collections.LinkedIOList;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-12 linked-addmult
 *
 * What the code should do:
 * IOList.addMultipleNew(count, initializer) (default at IOList.java:566-569) should
 * append `count` freshly created elements to the list: each element is a distinct
 * instance produced by getElementType().make(), the initializer is applied once per
 * instance, and the list's reported size grows by count (as add()/add(index)/remove()
 * all do via deltaSize, LinkedIOList.java:200,212,226,246,257).
 *
 * What it actually does:
 * LinkedIOList.addMultipleNew(long, UnsafeConsumer) (LinkedIOList.java:300-324):
 *   - line 304: `T val = getElementType().make()` creates ONE instance,
 *   - lines 309-316: the loop allocates all `count` IONodes with that same instance
 *     (`chainStart = allocNode(val, nextNode)`, line 315) and invokes the initializer
 *     `count` times on that one instance (line 311), so every node is a snapshot of
 *     the shared instance's state at allocation time,
 *   - the method never calls deltaSize(count) (compare the deltaSize calls in add at
 *     :226 and remove at :246), so size() is left stale.
 * Known-broken: CheckIOList.java:244-255 disables it (the 1-arg overload throws
 * UnsupportedOperationException, the 2-arg body is commented out).
 *
 * Why the test fails:
 * 1. staleSize: after addMultipleNew(5) on an empty list, size() is still 0 because
 *    deltaSize(5) was never written (no deltaSize call in LinkedIOList.java:300-324).
 * 2. sharedInstance: with an initializer that stores an increasing value, a correct
 *    implementation stores element i with value i, so iteration yields [0,1,2,3,4].
 *    The buggy code mutates the single shared instance 0->1->2->3->4 (line 311) and
 *    serializes it into a new node after each mutation (line 315); the chain is built
 *    in reverse (head = last allocation), so the stored values come out [4,3,2,1,0]
 *    instead of distinct elements [0,1,2,3,4].
 */
public class ReproLinkedAddMultTests{

    static{
        IOInstance.allowFullAccessI(MethodHandles.lookup());
    }

    /**
     * Managed element with a no-arg ctor (required by getElementType().make(),
     * LinkedIOList.java:304) and a mutable field so the initializer's effect is
     * observable per node.
     */
    @IOValue
    public static class El extends IOInstance.Managed<El>{
        private int val = 0;
        public El(){ }
        public int val(){
            return val;
        }
        public void setVal(int val){
            this.val = val;
        }
    }

    @Test
    public void addMultipleNewMustReportUpdatedSize() throws IOException{
        LinkedIOList<El> list = Cluster.emptyMem().roots().request("list", LinkedIOList.class, El.class);

        list.addMultipleNew(5);

        // BUG: LinkedIOList.addMultipleNew (LinkedIOList.java:300-324) never calls
        // deltaSize(count), so the list still reports 0 elements after adding 5.
        assertThat(list.size())
            .as("addMultipleNew(5) must grow the reported size from 0 to 5, but the deltaSize(count) call is missing (LinkedIOList.java:300-324)")
            .isEqualTo(5);
    }

    @Test
    public void addMultipleNewMustCreateDistinctInitializedElements() throws IOException{
        LinkedIOList<El> list = Cluster.emptyMem().roots().request("list", LinkedIOList.class, El.class);

        var seq = new AtomicInteger();
        list.addMultipleNew(5, el -> el.setVal(seq.getAndIncrement()));

        var seen = new ArrayList<Integer>();
        var it = list.iterator();
        while(it.hasNext()){
            seen.add(it.ioNext().val());
        }

        // BUG: one shared instance is created (LinkedIOList.java:304) and the
        // initializer mutates it 0..4 (LinkedIOList.java:311); each node is allocated
        // with that instance's current state (LinkedIOList.java:315) and the chain is
        // built in reverse, so iteration yields [4,3,2,1,0] instead of the distinct
        // elements [0,1,2,3,4] a correct per-instance implementation would produce.
        assertThat(seen)
            .as("element i must be the distinct instance initialized with i; a single shared instance makes every node a snapshot of it (LinkedIOList.java:304,311,315)")
            .containsExactly(0, 1, 2, 3, 4);
    }
}
