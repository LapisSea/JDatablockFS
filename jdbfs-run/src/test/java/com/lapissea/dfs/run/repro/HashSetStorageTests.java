package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DefragmentManager;
import com.lapissea.dfs.objects.collections.IOSet;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

public class HashSetStorageTests{
	@DataProvider
	public Object[][] removalModes(){
		return new Object[][]{{false}, {true}};
	}

	@Test(dataProvider = "removalModes")
	public void emptyingAndRefillingReclaimsNodeChains(boolean clear) throws IOException{
		var cluster = Cluster.emptyMem();
		var set = cluster.roots().<IOSet<Integer>>request(1, IOSet.class, Integer.class);
		cluster.scanGarbage(DefragmentManager.FreeFoundAction.ERROR);

		for(int cycle = 0; cycle<3; cycle++){
			int first = cycle*64;
			for(int i = first; i<first + 64; i++){
				assertThat(set.add(i)).isTrue();
			}
			assertThat(set.size()).isEqualTo(64);
			if(clear){
				set.clear();
			}else{
				for(int i = first; i<first + 64; i++){
					assertThat(set.remove(i)).isTrue();
				}
			}
			assertThat(set.size()).isZero();
			assertThat(set.iterator().hasNext()).isFalse();
			for(int i = first; i<first + 64; i++){
				assertThat(set.contains(i)).isFalse();
			}
			// Unreachable allocations must be registered as free, including referenced node chains.
			cluster.scanGarbage(DefragmentManager.FreeFoundAction.ERROR);
		}
		set.clear();
		cluster.scanGarbage(DefragmentManager.FreeFoundAction.ERROR);
	}
}

