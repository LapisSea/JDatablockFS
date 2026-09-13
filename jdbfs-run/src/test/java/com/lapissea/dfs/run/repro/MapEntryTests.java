package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.collections.HashIOMap;
import com.lapissea.dfs.objects.collections.IOMap;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

public class MapEntryTests{
	@DataProvider
	public Object[][] entries(){
		return new Object[][]{{"key", 42}, {null, 42}, {"key", null}, {null, null}};
	}

	@Test(dataProvider = "entries")
	public void equalEntriesHaveEqualHashCodes(String key, Integer value){
		var first = IOMap.IOEntry.of(key, value);
		var second = IOMap.IOEntry.of(key, value);
		assertThat(first).isEqualTo(second);
		assertThat(second).isEqualTo(first);
		assertThat(first.hashCode()).isEqualTo(second.hashCode());
	}

	@Test
	public void bothKeyAndValueContributeToHashCode(){
		var entry = IOMap.IOEntry.of("key", 1);
		var otherValue = IOMap.IOEntry.of("key", 2);
		var otherKey = IOMap.IOEntry.of("other", 1);
		assertThat(entry).isNotEqualTo(otherValue).isNotEqualTo(otherKey);
		// These selected inputs have distinct hashes; unequal entries may collide in general.
		assertThat(entry.hashCode()).isNotEqualTo(otherValue.hashCode()).isNotEqualTo(otherKey.hashCode());
	}

	@Test
	public void liveEntriesMatchDetachedEntryEqualityAndHash() throws IOException{
		var map = Cluster.emptyMem().roots().<HashIOMap<Integer, Integer>>request(1, HashIOMap.class, Integer.class, Integer.class);
		for(int value : new int[]{100, 200}){
			map.put(7, value);
			var live = map.getEntry(7);
			var detached = IOMap.IOEntry.of(7, value);
			assertThat(live).isNotNull().isEqualTo(detached);
			assertThat(detached).isEqualTo(live);
			assertThat(live.hashCode()).isEqualTo(detached.hashCode());
		}
	}
}

