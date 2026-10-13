package com.lapissea.dfs.core;

import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.FreeChunk;
import com.lapissea.dfs.core.chunk.PhysicalExtent;
import com.lapissea.dfs.core.chunk.PhysicalExtentWalker;
import com.lapissea.dfs.io.impl.FileRandomAccessData;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.MemoryWalker;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.fields.RefField;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds long chunk chains and attributes them to the exact object and field that owns them.
 * Run with -Dchain.file=/path/to.db [-Dchain.min=9] [-Dchain.detail=40]
 */
public class ChainLengthAnalysisTest{
	
	record ChainOrigin(String rootId, String owner, String field, long headPtr){ }
	
	record Chain(ChainOrigin origin, int steps){
		int length(){ return steps + 1; }
	}
	
	private static class Record implements MemoryWalker.PointerRecord{
		final String      rootId;
		final List<Chain> chains = new ArrayList<>();
		ChainOrigin curOrigin;
		int         curSteps;
		
		Record(String rootId){ this.rootId = rootId; }
		
		private void closeCur(){
			if(curOrigin != null){
				chains.add(new Chain(curOrigin, curSteps));
				curOrigin = null;
				curSteps = 0;
			}
		}
		
		@Override
		public <T extends IOInstance<T>> int log(Reference ref, T instance, RefField<T, ?> field, Reference value, Holder holder){
			closeCur();
			if(value.getOffset() == 0 && !value.getPtr().isNull())
				curOrigin = new ChainOrigin(rootId, instance.getClass().getSimpleName(), field.getName(), value.getPtr().getValue());
			return MemoryWalker.CONTINUE;
		}
		
		@Override
		public <T extends IOInstance<T>> int logChunkPointer(Reference ref, T instance, IOField<T, ChunkPointer> field, ChunkPointer value, Holder holder){
			if(instance instanceof Chunk){
				curSteps++;
				return MemoryWalker.CONTINUE;
			}
			closeCur();
			if(!value.isNull())
				curOrigin = new ChainOrigin(rootId, instance.getClass().getSimpleName(), field.getName(), value.getValue());
			return MemoryWalker.CONTINUE;
		}
	}
	
	@Test
	public void analyze() throws Exception{
		String file = System.getProperty("chain.file");
		if(file == null) throw new SkipException("set -Dchain.file=/path/to.db");
		int minChain = Integer.getInteger("chain.min", 9);
		int detail   = Integer.getInteger("chain.detail", 40);
		
		var storage  = new FileRandomAccessData(Path.of(file).toFile(), true);
		var cluster  = new Cluster(storage);
		var provider = cluster.roots().getDataProvider();
		
		var allChains = new ArrayList<Chain>();
		int roots     = 0;
		for(var entry : cluster.roots().listAll()){
			roots++;
			Object root = entry.getValue();
			if(!(root instanceof IOInstance.Unmanaged)) continue;
			var rec = new Record(String.valueOf(entry.getKey()));
			try{
				//noinspection rawtypes, unchecked
				new MemoryWalker((IOInstance.Unmanaged)root, false, rec).walk();
			}catch(Throwable e){
				System.out.println("WALK-FAIL root " + rec.rootId + " : " + e);
			}
			rec.closeCur();
			allChains.addAll(rec.chains);
		}
		
		var hist = new int[]{0, 0, 0, 0, 0};// 1, 2-4, 5-8, 9-32, 33+
		for(var ch : allChains){
			int l = ch.length();
			if(l == 1) hist[0]++;
			else if(l<=4) hist[1]++;
			else if(l<=8) hist[2]++;
			else if(l<=32) hist[3]++;
			else hist[4]++;
		}
		System.out.println("roots: " + roots + " chains: " + allChains.size());
		System.out.println("chain length histogram [1, 2-4, 5-8, 9-32, 33+]: " + hist[0] + ", " + hist[1] + ", " + hist[2] + ", " + hist[3] + ", " + hist[4]);
		
		var long_ = allChains.stream().filter(c -> c.length()>=minChain).sorted(Comparator.comparingInt(Chain::length).reversed()).toList();
		System.out.println("chains >= " + minChain + ": " + long_.size());
		for(var ch : long_.stream().limit(30).toList())
			System.out.println("  len=" + ch.length() + " root=" + ch.origin().rootId() + " " + ch.origin().owner() + "." + ch.origin().field() + " head=*" + ch.origin().headPtr());
		
		if(!long_.isEmpty())
			dumpChain(provider, long_.getFirst(), detail);
		
		System.out.println("\n=== physical extent scan ===");
		physicalScan(provider);
		
		try{
			storage.close();
		}catch(Throwable e){
			System.out.println("close on read-only ignored: " + e);
		}
	}
	
	static void dumpChain(DataProvider provider, Chain chain, int detail) throws IOException{
		var top = chain.origin();
		System.out.println("\n=== full walk of longest chain: " + top + " ===");
		long      ptr       = top.headPtr();
		Set<Long> seen      = new HashSet<>();
		var       caps      = new ArrayList<Long>();
		var       ptrs      = new ArrayList<Long>();
		var       sizes     = new ArrayList<Long>();
		long      sumCap    = 0, sumSize = 0;
		int       gapZero   = 0, gapNeg = 0, gapPos = 0;
		long      gapPosSum = 0;
		long      prevEnd   = -1;
		while(ptr != 0 && seen.add(ptr)){
			var extent = PhysicalExtent.read(provider, ChunkPointer.of(ptr));
			if(!(extent instanceof Chunk ch)){
				System.out.println("  walk ends at *" + ptr + " : " + extent.getClass().getSimpleName());
				break;
			}
			long cap = ch.getCapacity(), size = ch.getSize(), hdr = ch.getHeaderSize();
			caps.add(cap);
			ptrs.add(ptr);
			sizes.add(size);
			sumCap += cap;
			sumSize += size;
			if(prevEnd != -1){
				long gap = ptr - prevEnd;
				if(gap == 0) gapZero++;
				else if(gap<0) gapNeg++;
				else{
					gapPos++;
					gapPosSum += gap;
				}
			}
			prevEnd = ch.getPtr().getValue() + hdr + cap;
			var next = ch.getNextPtr();
			if(next.isNull()) break;
			ptr = next.getValue();
		}
		int n = caps.size();
		System.out.println("steps walked: " + n + (seen.size()>n? "" : " (cycle: " + (seen.size() - n) + ")"));
		System.out.println("capacity sum: " + sumCap + "  size sum: " + sumSize);
		System.out.println("gaps between consecutive chain elements: packed(adjacent)=" + gapZero + " backward=" + gapNeg + " forward=" + gapPos + " (forward gap total: " + gapPosSum + ")");
		
		int  cSmall      = 0, c64 = 0, c128 = 0, c512 = 0, c2k = 0, c8192 = 0, c16k = 0, c64k = 0, cMax = 0;
		long capOtherSum = 0;
		for(long cap : caps){
			if(cap<=63) cSmall++;
			else if(cap<=127) c64++;
			else if(cap<=511) c128++;
			else if(cap<=2047) c512++;
			else if(cap<=8191) c2k++;
			else if(cap == 8192) c8192++;
			else if(cap<=16383) c16k++;
			else if(cap<65535) c64k++;
			else cMax++;
		}
		System.out.println("capacity histogram [<64]=" + cSmall + " [64-127]=" + c64 + " [128-511]=" + c128 + " [512-2047]=" + c512 +
		                   " [2048-8191]=" + c2k + " [==8192]=" + c8192 + " [8193-16383]=" + c16k + " [16384-65534]=" + c64k + " [>=65535]=" + cMax);
		
		int patternPairs = 0;
		for(int i = 0; i + 2<n; i++)
			if(caps.get(i + 1) == 8192 && caps.get(i + 2) == caps.get(i) + 4) patternPairs++;
		System.out.println("(c, 8192, c+4) pattern occurrences: " + patternPairs);
		
		int[] marks = {0, n/4, n/2, Math.max(0, n - 12)};
		for(int m : marks){
			System.out.println("--- segment at #" + m + " ---");
			for(int i = m; i<Math.min(m + 12, n) && i<m + detail; i++){
				long gap = i == 0? 0 : ptrs.get(i) - (ptrs.get(i - 1) + sizes.get(i - 1) + caps.get(i - 1)*0 + (caps.get(i - 1)>=0? 10 : 10));
				System.out.printf("  #%d *%d cap=%d size=%d%n", i, ptrs.get(i), caps.get(i), sizes.get(i));
			}
		}
	}
	
	static void physicalScan(DataProvider provider) throws IOException{
		long start      = provider.getFirstChunk().getPtr().getValue();
		int  chunks     = 0, frees = 0, other = 0;
		long chunkBytes = 0, freeBytes = 0, maxFree = 0;
		int  fSmall     = 0, f64 = 0, f128 = 0, f512 = 0, f2k = 0, f8k = 0, f64k = 0, fBig = 0;
		try{
			for(var extent : new PhysicalExtentWalker(provider, ChunkPointer.of(start))){
				long siz = extent.dataEnd() - extent.getPtr().getValue();
				if(extent instanceof Chunk){
					chunks++;
					chunkBytes += siz;
				}else if(extent instanceof FreeChunk){
					frees++;
					freeBytes += siz;
					maxFree = Math.max(maxFree, siz);
					if(siz<=63) fSmall++;
					else if(siz<=127) f64++;
					else if(siz<=511) f128++;
					else if(siz<=2047) f512++;
					else if(siz<=8191) f2k++;
					else if(siz<=65534) f8k++;
					else if(siz<1024*1024) f64k++;
					else fBig++;
				}else other++;
			}
		}catch(Throwable e){
			System.out.println("scan aborted: " + e);
		}
		System.out.println("used chunk extents: " + chunks + " (" + chunkBytes + " bytes), free extents: " + frees + " (" + freeBytes + " bytes), other: " + other);
		System.out.println("free extent histogram [<64]=" + fSmall + " [64-127]=" + f64 + " [128-511]=" + f128 + " [512-2047]=" + f512 +
		                   " [2048-8191]=" + f2k + " [8192-65534]=" + f8k + " [65535-1MB)=" + f64k + " [>=1MB]=" + fBig + " maxFree=" + maxFree);
	}
}
