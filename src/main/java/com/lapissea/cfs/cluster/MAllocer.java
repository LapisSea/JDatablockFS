package com.lapissea.cfs.cluster;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.objects.IOList;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import static com.lapissea.cfs.Config.*;

abstract class MAllocer{
	
	private static final class PushPop extends MAllocer{
		
		@Override
		public Chunk alloc(Cluster cluster, IOList<ChunkPointer> freeChunks, long requestedSize, boolean disableResizing, Predicate<Chunk> approve) throws IOException{
			
			IOInterface data        =cluster.getData();
			int         minChunkSize=cluster.getMinChunkSize();
			
			ChunkPointer ptr=new ChunkPointer(data.getSize());
			
			Chunk fakeChunk=new Chunk(cluster, ptr, Math.max(minChunkSize, requestedSize), calcPtrSize(cluster, disableResizing));
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
			try{
				var cluster=chunk.cluster;
				if(!cluster.isLastPhysical(chunk)) return false;
				
				List<ChunkPointer> toYeet=new ArrayList<>();
				toYeet.add(chunk.getPtr());
				
				Chunk last=chunk;
				while(true){
					long target=last.getPtr().getValue();
					int  index =freeChunks.find(ptr->ptr!=null&&target==cluster.getChunk(ptr).dataEnd());
					if(index==-1) break;
					var ptr=freeChunks.getElement(index);
					last=cluster.getChunk(ptr);
					toYeet.add(ptr);
					freeChunks.removeElement(index);
				}
				
				var chunkCache=cluster.chunkCache;
				var data      =cluster.getData();
				
				LogUtil.println("freeing deallocated", toYeet);
				var ptr=last.getPtr();
				toYeet.forEach(chunkCache::remove);
				data.setCapacity(ptr.getValue());

//				int index=freeChunks.find(p->p!=null&&ptr.equals(cluster.getChunk(p).dataEnd()));
//				if(index!=-1){
//					freeChunks.setElement(index, null);
//				}
				
				cluster.validate();
				return true;
			}catch(Throwable e){
				throw new IOException("Failed to pop "+chunk, e);
			}
		}
	}
	
	private static final class ListingAddConsume extends MAllocer{
		
		@Override
		public Chunk alloc(Cluster cluster, IOList<ChunkPointer> freeChunks, long requestedCapacity, boolean disableResizing, Predicate<Chunk> approve) throws IOException{
			
			int minChunkSize=cluster.getMinChunkSize();
			
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
				
				largest.modifyAndSave(c->c.setCapacityConfident(freeCapacity));
				
				LogUtil.println("aloc reuse split", largest, " -> ", chunkUse);
				
				return chunkUse;
			}
			
			return null;
		}
		
		private void merge(Map<ChunkPointer, Chunk> chunkCache, Chunk target, Chunk next) throws IOException{
			target.requireReal();
			next.requireReal();
			
			long cap=next.dataEnd()-target.dataStart();
			
			try{
				target.setCapacity(cap);
			}catch(BitDepthOutOfSpaceException e){
				target.setBodyNumSize(NumberSize.bySize(cap));
				cap=next.dataEnd()-target.dataStart();
				assert cap>0;
				target.setCapacityConfident(cap);
			}
			target.syncStruct();
			destroyChunk(chunkCache, next);
			
		}
		
		@Override
		public boolean dealloc(Chunk toFree, IOList<ChunkPointer> freeChunks) throws IOException{
			Cluster                  cluster   =toFree.cluster;
			Map<ChunkPointer, Chunk> chunkCache=cluster.chunkCache;
			
			toFree.modifyAndSave(ch->{
				ch.setSize(0);
				ch.clearNextPtr();
			});
			
			toFree.zeroOutCapacity();
			
			
			Chunk next     =toFree.nextPhysical();
			int   nextIndex=-1;
			
			if(next!=null){
				if(next.isUsed()) next=null;
				else{
					nextIndex=freeChunks.indexOf(next.getPtr());
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
			
			if(next!=null){
				if(prev!=null){
					LogUtil.println("free triple merge", prev, toFree, next);
					freeChunks.setElement(nextIndex, null);
					
					merge(chunkCache, prev, next);
					destroyChunk(chunkCache, toFree);
				}else{
					LogUtil.println("free merge", toFree, next);
					freeChunks.setElement(nextIndex, toFree.getPtr());
					merge(chunkCache, toFree, next);
				}
			}else{
				if(prev!=null){
					LogUtil.println("free merge", prev, toFree);
					merge(chunkCache, prev, toFree);
				}else{
					LogUtil.println("free list", toFree);
					int emptyIndex=freeChunks.indexOf(null);
					if(emptyIndex!=-1){
						freeChunks.setElement(emptyIndex, toFree.getPtr());
					}else{
						freeChunks.addElement(toFree.getPtr());
					}
				}
			}
			
			
			if(DEBUG_VALIDATION){
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
		return cluster.calcPtrSize(disableNext);
	}
	
	public abstract Chunk alloc(Cluster cluster, IOList<ChunkPointer> freeChunks, long requestedSize, boolean disableResizing, Predicate<Chunk> approve) throws IOException;
	
	public abstract boolean dealloc(Chunk chunk, IOList<ChunkPointer> freeChunks) throws IOException;
	
}
