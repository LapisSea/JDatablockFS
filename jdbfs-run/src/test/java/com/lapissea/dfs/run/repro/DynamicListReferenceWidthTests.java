package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.run.TestUtils;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DefragmentManager;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.io.instancepipe.FixedVaryingStructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.TypedReference;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.type.field.VaryingSize;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

public class DynamicListReferenceWidthTests{
	@Test
	public void cachedNestedPipesReportIdsInTheirEnclosingLayout() throws Exception{
		var first = pointerFailure(0);
		var shifted = pointerFailure(5);
		assertThat(shifted.tooSmallIdMap.keySet().stream().map(size -> size.id).toList())
			.containsExactlyInAnyOrderElementsOf(first.tooSmallIdMap.keySet().stream().map(size -> size.id + 5).toList());
		assertThat(shifted.tooSmallIdMap.values()).containsOnly(NumberSize.SHORT);
	}
	private static VaryingSize.TooSmall pointerFailure(int prefix) throws Exception{
		var sizes = VaryingSize.Provider.record((max, pointer, id) -> max.min(NumberSize.BYTE));
		for(int i = 0; i<prefix; i++) sizes.provide(NumberSize.LONG, null, false);
		var pipe = FixedVaryingStructPipe.tryVarying(TypedReference.STRUCT, sizes);
		try(var io = MemoryData.empty().io()){
			pipe.write(Cluster.emptyMem(), io, new TypedReference(ChunkPointer.of(300).makeReference(), 1));
			throw new AssertionError("BYTE pointer should report a widening requirement");
		}catch(VaryingSize.TooSmall error){
			return error;
		}
	}

	@Test(timeOut = 30_000)
	public void insertionRetriesWhenWideningMovesPayloadAcrossAnotherPointerBoundary() throws Exception{
		var cluster = Cluster.emptyMem();
		IOList<Object> list = cluster.roots().request("values", IOList.class, Object.class);
		var expected = new ArrayList<Object>();
		var random = new Random(0x517ACEL);
		for(int id = 0; id<120; id++){
			Object value;
			if(id%3 == 1){
				value = id*7919L;
			}else{
				var text = new StringBuilder("value-" + id + ":");
				int length = 96 + random.nextInt(240);
				for(int i = 0; i<length; i++) text.append((char)('a' + random.nextInt(26)));
				value = text.toString();
			}
			list.add(value);
			expected.add(value);
			assertThat(list.size()).isEqualTo(expected.size());
			assertThat(list.get(id)).isEqualTo(value);
		}
		// Failed candidate writes must leave no orphaned payload allocations.
		cluster.scanGarbage(DefragmentManager.FreeFoundAction.ERROR);
		var reopened = TestUtils.reopenMem(cluster);
		IOList<Object> restored = reopened.roots().request("values", IOList.class, Object.class);
		assertThat(restored.size()).isEqualTo(expected.size());
		for(int index = 0; index<expected.size(); index++){
			assertThat(restored.get(index)).isEqualTo(expected.get(index));
		}
		reopened.scanGarbage(DefragmentManager.FreeFoundAction.ERROR);
	}
}
