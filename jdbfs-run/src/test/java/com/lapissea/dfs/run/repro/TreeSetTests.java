package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.objects.collections.IOTreeSet;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

public class TreeSetTests{
	@Test
	public void iterationIsSortedAcrossMutations() throws IOException{
		var set = Cluster.emptyMem().roots().<IOTreeSet<Integer>>request(1, IOTreeSet.class, Integer.class);
		var expected = new TreeSet<Integer>();
		for(int value : new int[]{50, 10, 90, 20, 80, 30, 10}){
			assertThat(set.add(value)).isEqualTo(expected.add(value));
			assertContents(set, expected);
		}
		for(int value : new int[]{50, 10, 90, 123}){
			assertThat(set.remove(value)).isEqualTo(expected.remove(value));
			assertContents(set, expected);
		}
		for(int value : new int[]{15, 95, 5}){
			assertThat(set.add(value)).isEqualTo(expected.add(value));
			assertContents(set, expected);
		}
	}

	private static void assertContents(IOTreeSet<Integer> set, TreeSet<Integer> expected) throws IOException{
		assertThat(set.size()).isEqualTo(expected.size());
		assertThat(set.iterator().toList()).containsExactlyElementsOf(expected);
	}

	@Test
	public void backingPoolsReuseFreedSlots() throws Exception{
		var set = Cluster.emptyMem().roots().<IOTreeSet<Integer>>request(1, IOTreeSet.class, Integer.class);
		var values = pool(set, "values");
		var nodes = pool(set, "nodes");
		assertThat(set.add(1)).isTrue();
		assertThat(set.add(2)).isTrue();
		long initialValues = values.size();
		long initialNodes = nodes.size();

		// Keeping two live elements prevents a fully drained pool from masking slot reuse.
		for(int cycle = 0; cycle<50; cycle++){
			assertThat(set.remove(cycle + 1)).isTrue();
			assertThat(set.add(cycle + 3)).isTrue();
			assertThat(set.size()).isEqualTo(2);
			assertThat(set.iterator().toList()).containsExactly(cycle + 2, cycle + 3);
			assertThat(values.size()).as("values pool at cycle %d", cycle).isLessThanOrEqualTo(initialValues + 2);
			assertThat(nodes.size()).as("nodes pool at cycle %d", cycle).isLessThanOrEqualTo(initialNodes + 2);
		}
	}

	@Test
	public void backingPoolsStayBoundedAcrossDrainAndRefill() throws Exception{
		var set = Cluster.emptyMem().roots().<IOTreeSet<Integer>>request(1, IOTreeSet.class, Integer.class);
		var values = pool(set, "values");
		var nodes = pool(set, "nodes");
		for(int cycle = 0; cycle<30; cycle++){
			int first = cycle*2;
			assertThat(set.add(first)).isTrue();
			assertPoolBound(values, nodes, cycle);
			assertThat(set.add(first + 1)).isTrue();
			assertPoolBound(values, nodes, cycle);
			assertThat(set.remove(first)).isTrue();
			assertPoolBound(values, nodes, cycle);
			assertThat(set.iterator().toList()).containsExactly(first + 1);
			assertThat(set.remove(first + 1)).isTrue();
			assertPoolBound(values, nodes, cycle);
			assertThat(set.size()).isZero();
			assertThat(set.iterator().hasNext()).isFalse();
		}
	}

	private static void assertPoolBound(IOList<?> values, IOList<?> nodes, int cycle){
		assertThat(values.size()).as("values pool at cycle %d", cycle).isLessThanOrEqualTo(2);
		assertThat(nodes.size()).as("nodes pool at cycle %d", cycle).isLessThanOrEqualTo(2);
	}

	// Public size reports live entries; backing pool sizes also expose unreused storage slots.
	private static IOList<?> pool(IOTreeSet<?> set, String name) throws ReflectiveOperationException{
		var field = IOTreeSet.class.getDeclaredField(name);
		field.setAccessible(true);
		return (IOList<?>)field.get(set);
	}
}

