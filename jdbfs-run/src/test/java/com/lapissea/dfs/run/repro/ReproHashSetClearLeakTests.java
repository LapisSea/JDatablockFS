package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DefragmentManager;
import com.lapissea.dfs.objects.collections.IOSet;
import org.testng.annotations.Test;

import java.io.IOException;

/**
 * Reproduction test for BUG-17 (hashset-clear-leak).
 *
 * Claimed defect:
 *   IOHashSet.clear() (jdbfs-core/src/main/java/com/lapissea/dfs/objects/collections/IOHashSet.java:161-170)
 *   calls oldData.free() at IOHashSet.java:169 WITHOUT the preceding oldData.clear() that the sibling
 *   path IOHashSet.grow() performs (IOHashSet.java:122-123). The report claims free() releases only
 *   the list's own chain and NOT the IONode chains stored as elements, so every clear() of a
 *   non-empty set would leak each element's node chunks.
 *
 * What the code is supposed to do:
 *   clear() empties the set and must release every chunk the old bucket array held, including the
 *   IONode chunk chain of each stored element (each element is an IONode with its own allocated
 *   chain, see IOHashSet.allocByValue -> IONode.allocValNode, IONode.java:255-269).
 *
 * What it actually does (verified):
 *   oldData.free() resolves to IOInstance.Unmanaged.free() (IOInstance.java:597), which calls
 *   MemoryOperations.freeSelfAndReferenced (MemoryOperations.java:921-925). That method walks the
 *   list with a MemoryWalker that records ALL referenced chains: for
 *   ContiguousIOList<IONode<T>>, getUnmanagedReferenceWalkCommands (ContiguousIOList.java:307-334)
 *   emits one REF_FIELD walk command per element (ValueStorage.UnmanagedInstance, line 316) and the
 *   walker additionally follows each node's next pointer. MemoryManager.free therefore receives the
 *   list's own chain AND every element node chain. The missing oldData.clear() is harmless - no leak.
 *
 * Why this test fails (if the reported defect were real):
 *   After filling the set and calling clear(), the leaked node chunks would be neither reachable from
 *   any root nor registered as free, so Cluster.scanGarbage(ERROR) (DefragmentManager.scanFreeChunks,
 *   DefragmentManager.java:482-510) would throw MalformedFile "found unknown free chunks: ...".
 *   With the current code the node chains ARE freed, so this test PASSES (bug not reproduced).
 *   The two control tests prove the scan harness itself is sound: a fresh cluster reports no garbage,
 *   and the equivalent per-element remove() path (IOHashSet.remove() -> IONode.free()) leaves none.
 */
public class ReproHashSetClearLeakTests{

    private static final int ELEMENTS = 64;

    /**
     * The bug itself: clearing a non-empty IOHashSet must not leave the element node chunks
     * behind. With the defect present, scanGarbage(ERROR) throws MalformedFile for the leaked
     * IONode chains (they are unreferenced and not in the free-chunk list).
     */
    @Test
    public void clearFreesElementNodeChains() throws IOException{
        var cluster = Cluster.emptyMem();
        var set     = cluster.roots().<IOSet<Integer>>request(1, IOSet.class, Integer.class);

        for(int i = 0; i<ELEMENTS; i++){
            if(!set.add(i)){
                throw new AssertionError("add(" + i + ") unexpectedly failed");
            }
        }
        org.testng.Assert.assertEquals(set.size(), ELEMENTS, "set should hold " + ELEMENTS + " elements before clear");

        set.clear();
        org.testng.Assert.assertEquals(set.size(), 0, "set should be empty after clear");

        // Key assertion: with a correct clear() (oldData.clear() before oldData.free(), as in grow()),
        // every node chain is returned to the memory manager and no unknown free chunks exist.
        // BUG: IOHashSet.clear() (IOHashSet.java:169) skips oldData.clear(), so the element IONode
        // chains leak and this throws MalformedFile("found unknown free chunks: ...").
        cluster.scanGarbage(DefragmentManager.FreeFoundAction.ERROR);
    }

    /**
     * Control 1: a fresh cluster with a just-created set must report no garbage.
     * If this fails, the scan harness (not the bug) is at fault.
     */
    @Test
    public void freshClusterHasNoGarbage() throws IOException{
        var cluster = Cluster.emptyMem();
        cluster.roots().<IOSet<Integer>>request(1, IOSet.class, Integer.class);

        // Harness sanity: nothing has been freed yet, so the scan must find nothing.
        cluster.scanGarbage(DefragmentManager.FreeFoundAction.ERROR);
    }

    /**
     * Control 2: removing every element through the supported per-element path
     * (IOHashSet.remove() -> IONode.free(), IOHashSet.java:143-158) must also leave no garbage.
     * This shows the same end state (empty set) is garbage-free when the element chains are
     * actually freed, isolating clear() as the leaking path.
     */
    @Test
    public void removeFreesElementNodeChains() throws IOException{
        var cluster = Cluster.emptyMem();
        var set     = cluster.roots().<IOSet<Integer>>request(1, IOSet.class, Integer.class);

        for(int i = 0; i<ELEMENTS; i++){
            set.add(i);
        }
        for(int i = 0; i<ELEMENTS; i++){
            if(!set.remove(i)){
                throw new AssertionError("remove(" + i + ") unexpectedly failed");
            }
        }
        org.testng.Assert.assertEquals(set.size(), 0, "set should be empty after removing all elements");

        // Control assertion: the remove() path frees each node chain, so no garbage may be found.
        cluster.scanGarbage(DefragmentManager.FreeFoundAction.ERROR);
    }
}
