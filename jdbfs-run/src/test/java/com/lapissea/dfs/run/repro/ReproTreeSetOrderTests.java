package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.collections.IOTreeSet;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-13 treeset-order reproduction.
 *
 * WHAT IT SHOULD DO:
 *   IOTreeSet<T> is a binary search tree: each Node stores a valueIndex plus left/right
 *   child indices, and add()/remove() maintain the BST invariant (left < node < right).
 *   That invariant is explicitly checked by IOTreeSet.validate()
 *   (jdbfs-core/.../IOTreeSet.java:476-497, see the {@code c != c2} check at :491).
 *   A "TreeSet" by definition iterates its elements in ASCENDING (sorted) order.
 *
 * WHAT IT ACTUALLY DOES:
 *   IOTreeSet.iterator() (IOTreeSet.java:589-613) never walks the BST. It iterates the
 *   backing {@code values} IOList in physical SLOT order, skipping null slots
 *   (line 591: {@code var src = values.iterator();}). Every add() appends its value to
 *   the END of that list (IOTreeSet.addNode -> values.add(new Val<>(value)), :389-390),
 *   so slot order == insertion order. The iterator therefore yields elements in
 *   INSERTION order, not sorted order.
 *
 * WHY THE TEST FAILS:
 *   Inserting 50,10,90,20,80,30 builds a valid BST (validate() would pass) but stores
 *   the values slots as [50,10,90,20,80,30]. A correct TreeSet iterates
 *   [10,20,30,50,80,90]; this one iterates [50,10,90,20,80,30]. The ascending-order
 *   assertion below therefore fails.
 */
public class ReproTreeSetOrderTests{

    @Test
    public void iterationMustBeAscending() throws IOException{
        var provider = Cluster.emptyMem();
        var set = provider.roots().<IOTreeSet<Integer>>request(1, IOTreeSet.class, Integer.class);

        // Insert in deliberately scrambled (non-sorted) order.
        int[] inserted = {50, 10, 90, 20, 80, 30};
        for(int v : inserted){
            // each add must succeed on a fresh set with distinct values
            assertThat(set.add(v)).as("add(%d) should succeed", v).isTrue();
        }

        // Sanity: all six distinct elements are present, so an ordering failure is
        // about ORDER, not about missing/duplicated data.
        assertThat(set.size()).as("set size").isEqualTo(inserted.length);

        // Collect the iteration order produced by IOTreeSet.iterator().
        List<Integer> iterated = set.iterator().toList();

        // BUG: IOTreeSet must iterate in ascending order (it is a BST "TreeSet"),
        // but iterator() walks the values pool in insertion/slot order.
        // Actual order here is [50,10,90,20,80,30] -> this assertion FAILS.
        assertThat(iterated)
            .as("IOTreeSet.iterator() must yield ascending order, but returned %s", iterated)
            .containsExactly(10, 20, 30, 50, 80, 90);
    }
}
