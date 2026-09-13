package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.RandomIOReadOnly;
import com.lapissea.dfs.io.RangeIO;
import com.lapissea.dfs.io.impl.FileMemoryMappedData;
import com.lapissea.dfs.io.impl.FileRandomAccessData;
import com.lapissea.dfs.io.impl.MemoryData;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

public class RandomIOViewTests{
	private static byte[] pattern(int size){
		var data = new byte[size];
		for(int i = 0; i<size; i++) data[i] = (byte)(i*31 + 7);
		return data;
	}

	@DataProvider
	public Object[][] rangeLengths(){ return new Object[][]{{30}, {50}, {95}}; }

	@Test(dataProvider = "rangeLengths")
	public void trimAtRangeEndPreservesParent(int length) throws IOException{
		var bytes = pattern(100);
		var data = MemoryData.of(bytes.clone());
		try(var view = RangeIO.of(data.io(), 5, length)){
			view.setPos(100);
			assertThat(view.getPos()).isEqualTo(length);
			view.trim();
			assertThat(view.getSize()).isEqualTo(length);
			assertThat(data.getIOSize()).isEqualTo(bytes.length);
			assertThat(data.readAll()).isEqualTo(bytes);
		}
	}

	@Test
	public void trimInsideRangePreservesPrefix() throws IOException{
		var bytes = pattern(100);
		var data = MemoryData.of(bytes.clone());
		try(var view = RangeIO.of(data.io(), 5, 50)){
			view.setPos(20);
			view.trim();
			assertThat(view.getSize()).isEqualTo(20);
			assertThat(view.getPos()).isEqualTo(20);
			assertThat(data.readAll()).isEqualTo(java.util.Arrays.copyOf(bytes, 25));
		}
	}

	private static void assertReadsAcrossFlush(RandomIO io, byte[] expected) throws IOException{
		assertThat(io.setPos(0).readRemaining()).isEqualTo(expected);
		for(int i = 0; i<2; i++){
			io.setPos(17);
			io.flush();
			assertThat(io.getPos()).as("flush preserves cursor").isEqualTo(17);
			assertThat(io.setPos(0).readRemaining()).as("read after flush").isEqualTo(expected);
		}
	}

	@DataProvider
	public Object[][] fileBackends(){ return new Object[][]{{false}, {true}}; }

	@Test(dataProvider = "fileBackends")
	public void readOnlyFileRemainsUsableAfterFlush(boolean mapped) throws IOException{
		var path = Files.createTempFile("readonly-view", ".bin");
		var bytes = pattern(8192);
		try{
			try(var data = mapped? new FileMemoryMappedData(path.toFile()) :
			                       new FileRandomAccessData(path.toFile(), FileRandomAccessData.Mode.READ_WRITE)){
				try(var io = data.io()){
					io.write(bytes);
				}
				try(var ro = data.readOnlyIO()){
					assertReadsAcrossFlush(ro, bytes);
				}
				assertThat(data.readAll()).as("new session after closing view").isEqualTo(bytes);
			}
		}finally{
			Files.deleteIfExists(path);
		}
	}

	@Test
	public void readOnlyChainCanCloseNormallyAfterFlush() throws IOException{
		var cluster = Cluster.emptyMem();
		var chunk = AllocateTicket.bytes(4096).submit(cluster);
		var bytes = pattern(8192);
		try(var io = chunk.io()){
			io.write(bytes);
		}
		try(var ro = chunk.readOnlyIO()){
			assertReadsAcrossFlush(ro, bytes);
		}
		assertThat(chunk.readAll()).as("fresh chain after view closes").isEqualTo(bytes);
	}

	@Test
	public void readOnlyFlushDelegatesWithoutClosing() throws IOException{
		try(var source = MemoryData.of(pattern(32)).io()){
			int[] calls = new int[2];
			var proxy = (RandomIO)java.lang.reflect.Proxy.newProxyInstance(
				RandomIO.class.getClassLoader(), new Class<?>[]{RandomIO.class},
				(instance, method, args) -> {
					if(method.getName().equals("flush")){
						calls[0]++;
						return null;
					}
					if(method.getName().equals("close")){
						calls[1]++;
						return null;
					}
					try{
						return method.invoke(source, args);
					}catch(java.lang.reflect.InvocationTargetException e){
						throw e.getCause();
					}
				});
			try(var ro = new RandomIOReadOnly(proxy)){
				ro.flush();
				ro.flush();
				assertThat(calls).containsExactly(2, 0);
			}
			assertThat(calls).containsExactly(2, 1);
		}
	}
}
