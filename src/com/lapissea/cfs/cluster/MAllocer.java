package com.lapissea.cfs.cluster;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.objects.IOList;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.lapissea.cfs.Config.*;

abstract class MAllocer{
	
	private static final class PushPop extends MAllocer{
		
		@Override
		public Chunk alloc(Cluster cluster, IOList<ChunkPointer> freeChunks, long requestedSize, boolean disableResizing, Predicate<Chunk> approve) throws IOException{
			
			var chunkCache=cluster.chunkCache;
			var data      =cluster.getData();
			
			ChunkPointer ptr=new ChunkPointer(data.getSize());
			
			Chunk fakeChunk=new Chunk(cluster, ptr, requestedSize, calcPtrSize(cluster, disableResizing));
			if(!approve.test(fakeChunk)) return null;
			
			Chunk chunk=makeChunkReal(fakeChunk);
			assert data.getSize()==chunk.dataStart():data.getSize()+"=="+chunk.dataStart();
			
			data.ioAt(chunk.dataStart(), io->{
				Utils.zeroFill(io::write, chunk.getCapacity());
			});
			
			assert data.getSize()==chunk.dataEnd():data.getSize()+"=="+chunk.dataEnd();
			
			LogUtil.println("alloc append", chunk);
			
			return chunk;
		}
		
		@Override
		public boolean dealloc(Chunk chunk, IOList<ChunkPointer> freeChunks) throws IOException{
			var cluster=chunk.cluster;
			if(!cluster.isLastPhysical(chunk)) return false;
			
			var chunkCache=cluster.chunkCache;
			var data      =cluster.getData();
			
			LogUtil.println("freeing deallocated", chunk);
			var ptr=chunk.getPtr();
			chunkCache.remove(ptr);
			data.setCapacity(ptr.getValue());
			freeChunks.find(p->p!=null&&ptr.equals(cluster.getChunk(p).dataEnd()),
			                i->freeChunks.setElement(i, null));
			
			cluster.validate();
			return true;
		}
	}
	
	private static final class ListingAddConsume extends MAllocer{
		
		@Override
		public Chunk alloc(Cluster cluster, IOList<ChunkPointer> freeChunks, long requestedCapacity, boolean disableResizing, Predicate<Chunk> approve) throws IOException{
			
			var chunkCache  =cluster.chunkCache;
			var data        =cluster.getData();
			var minChunkSize=cluster.minChunkSize;
			
			if(cluster.isSafeMode()) return null;
			if(freeChunks.noneMatches(Objects::nonNull)) return null;
			
			UnsafeFunction<Chunk, Chunk, IOException> popEnd=chunk->{
				Chunk chunkUse=new Chunk(cluster, chunk.getPtr(), requestedCapacity, calcPtrSize(cluster, disableResizing));
				chunkUse.setLocation(new ChunkPointer(chunk.dataEnd()-chunkUse.totalSize()));
				return chunkUse;
			};
			
			Chunk largest=null;
			
			int  bestIndex   =-1;
			long bestOverAloc=Long.MAX_VALUE;
			
			for(int i=0;i<freeChunks.size();i++){
				var ptr=freeChunks.getElement(i);
				if(ptr==null) continue;
				
				Chunk chunk=cluster.getChunk(ptr);
				long  cap  =chunk.getCapacity();
				
				if(cap<requestedCapacity){
					continue;
				}
				
				if(!cluster.isSafeMode()){
					long maxSize =requestedCapacity+minChunkSize;
					long overAloc=maxSize-cap;
					if(overAloc>=0&&overAloc<bestOverAloc&&approve.test(chunk)){
						bestOverAloc=overAloc;
						bestIndex=i;
						if(overAloc==0) break;
					}
				}
				
				if(largest==null||largest.getCapacity()<cap){
					Chunk chunkUse=popEnd.apply(chunk);
					
					long freeCapacity=chunk.getCapacity()-chunkUse.totalSize();
					if(freeCapacity>=minChunkSize&&approve.test(chunk)){
						largest=chunk;
					}
				}
			}
			
			if(bestIndex!=-1){
				Chunk chunk=cluster.getChunk(freeChunks.getElement(bestIndex));
				
				chunk.modifyAndSave(c->c.setUsed(true));
				
				freeChunks.setElement(bestIndex, null);
				LogUtil.println("aloc reuse exact", chunk);
				return chunk;
			}
			
			if(largest!=null){
				Chunk chunkUse=makeChunkReal(popEnd.apply(largest));
				
				long freeCapacity=largest.getCapacity()-chunkUse.totalSize();
				assert freeCapacity>=minChunkSize;
				
				largest.modifyAndSave(c->c.setCapacity(freeCapacity));
				
				LogUtil.println("aloc reuse split", largest, " -> ", chunkUse);
				
				return chunkUse;
			}
			
			return null;
		}
		@Override
		public boolean dealloc(Chunk toFree, IOList<ChunkPointer> freeChunks) throws IOException{
			var cluster   =toFree.cluster;
			var chunkCache=cluster.chunkCache;
			var data      =cluster.getData();
			
			toFree.modifyAndSave(ch->{
				ch.setSize(0);
				ch.clearNextPtr();
			});
			
			toFree.zeroOutCapacity();
			
			UnsafeBiConsumer<Chunk, Chunk, IOException> merge=(target, next)->{
				target.requireReal();
				
				long cap=next.dataEnd()-target.dataStart();
				target.modifyAndSave(c->c.setCapacity(cap));
				destroyChunk(chunkCache, next);
			};
			
			Function<Chunk, long[]> rang=ch->new long[]{ch.getPtr().getValue(), ch.dataEnd()};
			
			Chunk next     =toFree.nextPhysical();
			int   nextIndex=-1;
			
			if(next!=null){
				if(next.isUsed()) next=null;
				else{
					var ptr=next.getPtr();
					nextIndex=freeChunks.indexOf(ptr);
					if(nextIndex==-1){
						next=null;
					}
				}
			}
			
			Chunk prev;
			{
				var freeOffset=toFree.getPtr().getValue();
				int prevIndex =freeChunks.find(v->v!=null&&freeOffset==cluster.getChunk(v).dataEnd());
				
				if(prevIndex!=-1){
					prev=cluster.getChunk(freeChunks.getElement(prevIndex));
				}else{
					prev=null;
				}
			}
			
			if(next!=null&&prev!=null){
				LogUtil.println("free triple merge", rang.apply(prev), rang.apply(toFree), rang.apply(next));
				freeChunks.setElement(nextIndex, null);
				
				merge.accept(prev, next);
				destroyChunk(chunkCache, toFree);
				
			}else if(prev!=null){
				LogUtil.println("free merge", rang.apply(prev), rang.apply(toFree));
				merge.accept(prev, toFree);
				
			}else if(next!=null){
				LogUtil.println("free merge", rang.apply(toFree), rang.apply(next));
				freeChunks.setElement(nextIndex, toFree.getPtr());
				merge.accept(toFree, next);
				
			}else{
				LogUtil.println("free list", toFree);
				int emptyIndex=freeChunks.indexOf(null);
				if(emptyIndex!=-1){
					freeChunks.setElement(emptyIndex, toFree.getPtr());
				}else{
					freeChunks.addElement(toFree.getPtr());
				}
			}
			
			
			if(DEBUG_VALIDATION){
//				if(freeChunks.size()>1){
//					freeChunks.validate();
//
//					List<long[]> ranges=new ArrayList<>();
//					for(int i=0;i<freeChunks.size();i++){
//						ChunkPointer c=freeChunks.getElement(i);
//						if(c==null) continue;
//
//						Chunk ch=cluster.getChunk(c);
//						ranges.add(rang.apply(ch));
//					}
//					ranges.sort(Comparator.comparingLong(e->e[0]));
//					long last=-1;
//					for(long[] range : ranges){
//						assert range[0]!=last:
//							TextUtil.toString(ranges)+"\n"+freeChunks;
//						last=range[1];
//					}
//				}
				cluster.validate();
			}
			return true;
		}
	}
	
	public static final MAllocer PUSH_POP        =new PushPop();
	public static final MAllocer FREE_ADD_CONSUME=new ListingAddConsume();
	
	public static final MAllocer AUTO=new MAllocer(){
		@Override
		public Chunk alloc(Cluster cluster, IOList<ChunkPointer> freeChunks, long requestedSize, boolean disableResizing, Predicate<Chunk> approve) throws IOException{
			Chunk chunk;
			
			chunk=FREE_ADD_CONSUME.alloc(cluster, freeChunks, requestedSize, disableResizing, approve);
			
			if(chunk==null){
				chunk=PUSH_POP.alloc(cluster, freeChunks, requestedSize, disableResizing, approve);
			}
			
			return chunk;
		}
		@Override
		public boolean dealloc(Chunk chunk, IOList<ChunkPointer> freeChunks) throws IOException{
			if(PUSH_POP.dealloc(chunk, freeChunks)) return true;
			if(FREE_ADD_CONSUME.dealloc(chunk, freeChunks)) return true;
			
			throw new NotImplementedException();
		}
	};
	
	protected Chunk makeChunkReal(Chunk chunk) throws IOException{
		chunk.writeStruct();
		
		Chunk read=chunk.cluster.getChunk(chunk.getPtr());
		assert read.equals(chunk);
		return read;
	}
	protected void destroyChunk(Map<ChunkPointer, Chunk> chunkCache, Chunk chunk) throws IOException{
		chunkCache.remove(chunk.getPtr());
		chunk.zeroOutHead();
	}
	
	protected NumberSize calcPtrSize(Cluster cluster, boolean disableNext) throws IOException{
		if(disableNext) return NumberSize.VOID;
		return NumberSize.bySize(cluster.getData().getSize()).next();
	}
	
	public abstract Chunk alloc(Cluster cluster, IOList<ChunkPointer> freeChunks, long requestedSize, boolean disableResizing, Predicate<Chunk> approve) throws IOException;
	public abstract boolean dealloc(Chunk chunk, IOList<ChunkPointer> freeChunks) throws IOException;
	
}
