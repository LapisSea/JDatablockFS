package com.lapissea.dfs.run;

import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

public class IOTransactionTests{
	
	private static MemoryData initialMem(int size){
		var b = new byte[size];
		for(int i = 0; i<size; i++) b[i] = (byte)i;
		return MemoryData.builder().withRaw(b).build();
	}
	
	@Test
	void simpleWriteRead() throws IOException{
		var mem = initialMem(4);
		
		try(var ignore = mem.openIOTransaction();
		    var io = mem.io()){
			io.setPos(1);
			io.write(new byte[]{11, 12});
			checkMismatchBin(mem, 0, 11, 12, 3);
		}
	}
	@Test
	void overwrite() throws IOException{
		var mem = initialMem(4);
		
		try(var ignore = mem.openIOTransaction();
		    var io = mem.io()){
			io.setPos(1);
			io.write(new byte[]{11, 12});
			checkMismatchBin(mem, 0, 11, 12, 3);
			io.setPos(1);
			io.write(new byte[]{111, 112});
			checkMismatchBin(mem, 0, 111, 112, 3);
		}
	}
	@Test
	void intersectRead() throws IOException{
		var mem = initialMem(6);
		
		try(var ignore = mem.openIOTransaction();
		    var io = mem.io()){
			io.setPos(1);
			io.write(new byte[]{11, 12, 13, 14});
			checkMismatchBin(mem, 0, 11, 12, 13, 14, 5);
			
			io.setPos(0);
			checkMismatchBin(io.readInts1(3), new byte[]{0, 11, 12});
			io.setPos(3);
			checkMismatchBin(io.readInts1(3), new byte[]{13, 14, 5});
		}
	}
	@Test
	void eventGapJoin() throws IOException{
		var mem = initialMem(8);
		
		try(var ignore = mem.openIOTransaction();
		    var io = mem.io()){
			
			io.setPos(1);
			io.write(new byte[]{11, 12});
			checkMismatchBin(mem, 0, 11, 12, 3, 4, 5, 6, 7);
			
			io.setPos(5);
			io.write(new byte[]{15, 16});
			checkMismatchBin(mem, 0, 11, 12, 3, 4, 15, 16, 7);
			
			io.setPos(3);
			io.write(new byte[]{13, 14});
			checkMismatchBin(mem, 0, 11, 12, 13, 14, 15, 16, 7);
			
		}
	}
	@Test
	void eventGapJoinOverrideBackward() throws IOException{
		var mem = initialMem(8);
		
		try(var ignore = mem.openIOTransaction();
		    var io = mem.io()){
			
			io.setPos(1);
			io.write(new byte[]{11, 12});
			checkMismatchBin(mem, 0, 11, 12, 3, 4, 5, 6, 7);
			
			io.setPos(5);
			io.write(new byte[]{15, 16});
			checkMismatchBin(mem, 0, 11, 12, 3, 4, 15, 16, 7);
			
			io.setPos(2);
			io.write(new byte[]{112, 13, 14});
			checkMismatchBin(mem, 0, 11, 112, 13, 14, 15, 16, 7);
			
		}
	}
	@Test
	void eventGapJoinOverrideForward() throws IOException{
		var mem = initialMem(8);
		
		try(var ignore = mem.openIOTransaction();
		    var io = mem.io()){
			
			io.setPos(1);
			io.write(new byte[]{11, 12});
			checkMismatchBin(mem, 0, 11, 12, 3, 4, 5, 6, 7);
			
			io.setPos(5);
			io.write(new byte[]{15, 16});
			checkMismatchBin(mem, 0, 11, 12, 3, 4, 15, 16, 7);
			
			io.setPos(3);
			io.write(new byte[]{13, 14, 115});
			checkMismatchBin(mem, 0, 11, 12, 13, 14, 115, 16, 7);
			
		}
	}
	@Test
	void eventMultiSquashBackwards() throws IOException{
		var e = initialMem(8);
		
		try(var ignore = e.openIOTransaction();
		    var io = e.io()){
			
			io.setPos(0);
			io.write(new byte[]{10});
			checkMismatchBin(e, 10, 1, 2, 3, 4, 5, 6, 7);
			io.setPos(2);
			io.write(new byte[]{12});
			checkMismatchBin(e, 10, 1, 12, 3, 4, 5, 6, 7);
			io.setPos(4);
			io.write(new byte[]{14, 15, 16});
			checkMismatchBin(e, 10, 1, 12, 3, 14, 15, 16, 7);
			
			io.setPos(0);
			io.write(new byte[]{110, 11, 112, 13});
			checkMismatchBin(e, 110, 11, 112, 13, 14, 15, 16, 7);
		}
	}
	@Test
	void eventMultiSquashBackwardsIntersect() throws IOException{
		var e = initialMem(8);
		
		try(var ignore = e.openIOTransaction();
		    var io = e.io()){
			
			io.setPos(0);
			io.write(new byte[]{10});
			checkMismatchBin(e, 10, 1, 2, 3, 4, 5, 6, 7);
			io.setPos(2);
			io.write(new byte[]{12});
			checkMismatchBin(e, 10, 1, 12, 3, 4, 5, 6, 7);
			io.setPos(4);
			io.write(new byte[]{14, 15, 16});
			checkMismatchBin(e, 10, 1, 12, 3, 14, 15, 16, 7);
			
			io.setPos(0);
			io.write(new byte[]{20, 11, 22, 13, 114});
			checkMismatchBin(e, 20, 11, 22, 13, 114, 15, 16, 7);
		}
	}
	
	@Test
	void fuzz(){
		int cap = 50;
		
		//Dumb brute force all possible edge cases
		TestUtils.randomBatch(500000, (rand, run) -> {
			int runIndex = 0;
			
			IOInterface data   = MemoryData.builder().withCapacity(cap + 10).build();
			IOInterface mirror = MemoryData.builder().withCapacity(cap + 10).build();
			
			try(var ignored = data.openIOTransaction()){
				var runSize = rand.nextInt(40);
				for(int j = 1; j<runSize + 1; j++){
					runIndex++;
					
					var before = mirror.readAll();
					
					if(rand.nextFloat()<0.1){
						var newSiz = rand.nextInt(cap*2) + 21;
						
						try(var io = mirror.io()){
							io.setCapacity(newSiz);
						}
						try(var io = data.io()){
							io.setCapacity(newSiz);
						}
					}else{
						
						int off = rand.nextInt((int)data.getIOSize() - 10);
						int siz = rand.nextInt(10);
						
						byte[] buf = new byte[siz];
						Arrays.fill(buf, (byte)j);
						
						mirror.write(off, false, buf);
						data.write(off, false, buf);
					}
					
					assertThat(data).extracting("IOSize").isEqualTo(data.getIOSize());
					
					for(int i0 = 0; i0<10; i0++){
						int i = i0;
						{
							var rSiz  = rand.nextInt(20);
							var rOff  = rand.nextInt((int)(data.getIOSize() - rSiz));
							var ref   = mirror.read(rOff, rSiz);
							var bytes = data.read(rOff, rSiz);
							assertThat(bytes).as(() -> "Failed read on: offset: " + rOff + " size: " + rSiz + " iter index: " + i + "\n" +
							                           mismatchBin(ref, bytes, true))
							                 .containsExactly(ref);
						}
						
						{
							var bOff  = rand.nextInt((int)(data.getIOSize()));
							var ref   = mirror.ioMap(io -> io.setPos(bOff).readInt1());
							var bytes = data.ioMap(io -> io.setPos(bOff).readInt1());
							assertThat(bytes).as(() -> "check iteration " + i).isEqualTo(ref);
						}
						
						var bSiz  = rand.nextInt(1, 9);
						var bOff  = rand.nextInt((int)(data.getIOSize() - bSiz));
						var ref   = mirror.ioMap(io -> io.setPos(bOff).readWord(bSiz));
						var bytes = data.ioMap(io -> io.setPos(bOff).readWord(bSiz));
						assertThat(bytes).as(() -> "check iteration " + i).isEqualTo(ref);
					}
					
					check(before, mirror, data, "post write");
				}
			}catch(Throwable e){
				throw new RuntimeException("failed on run==" + run + " && runIndex==" + runIndex, e);
			}
			check(new byte[0], mirror, data, "failed after cycle " + run);
		});
	}
	
	private void check(byte[] before, IOInterface expected, IOInterface data, String s){
		byte[] reference, bytes;
		try{
			reference = expected.readAll();
			bytes = data.readAll();
		}catch(Throwable e){
			throw new RuntimeException(s, e);
		}
		assertThat(bytes).as(() -> s + "\n" +
		                           "Before\n" +
		                           HexFormat.of().formatHex(before) + "\n" +
		                           mismatchBin(reference, bytes, true))
		                 .containsExactly(reference);
	}
	private static void checkMismatchBin(MemoryData expected, int... actual) throws IOException{
		var a = new byte[actual.length];
		for(int i = 0; i<actual.length; i++) a[i] = (byte)actual[i];
		checkMismatchBin(expected.readAll(), a);
	}
	private static void checkMismatchBin(byte[] expected, byte[] actual){
		if(Arrays.equals(expected, actual)) return;
		throw new AssertionError("\n" + mismatchBin(expected, actual, false));
	}
	private static String bTo3(byte val){
		var v = Integer.toString(Byte.toUnsignedInt(val));
		return "0".repeat(3 - v.length()) + v;
	}
	private static String mismatchBin(byte[] expected, byte[] actual, boolean hex){
		if(!hex){
			return "Expected / actual\n" +
			       Iters.rangeMap(0, expected.length, i -> bTo3(expected[i])).joinAsStr("-") + "\n" +
			       Iters.rangeMap(0, actual.length, i -> bTo3(actual[i])).joinAsStr("-") + "\n" +
			       Iters.rangeMap(0, Math.min(expected.length, actual.length), i -> expected[i] == actual[i]? "   " : "^^^").joinAsStr(" ");
			
		}
		return "Expected / actual\n" +
		       HexFormat.of().formatHex(expected) + "\n" +
		       HexFormat.of().formatHex(actual) + "\n" +
		       Iters.rangeMap(0, Math.min(expected.length, actual.length), i -> expected[i] == actual[i]? "  " : "^^").joinAsStr();
	}
	
}
