package com.lapissea.cfs.cluster;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.conf.AllocateTicket;
import com.lapissea.cfs.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.objects.IOList;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

import static com.lapissea.cfs.GlobalConfig.*;

abstract class MAllocer{
	
	private static final class PushPop extends MAllocer{
		
		@Override
		public Chunk alloc(Cluster cluster, IOList<ChunkPointer> freeChunks, AllocateTicket ticket) throws IOException{
			
			IOInterface data        =cluster.getData();
			int         minChunkSize=cluster.getMinChunkSize();
			
			ChunkPointer ptr=new ChunkPointer(data.getSize());
			
			Chunk fakeChunk=new Chunk(cluster, ptr, Math.max(minChunkSize, ticket.bytes()), calcPtrSize(cluster, ticket));
			fakeChunk.setIsUserData(ticket.userData()!=null);
			if(!ticket.approve(fakeChunk)) return null;
			
			Chunk chunk=makeChunkReal(fakeChunk);
			assert data.getSize()==chunk.dataStart():data.getSize()+"=="+chunk.dataStart();
			
			data.ioAt(chunk.dataStart(), io->{
				Utils.zeroFill(io::write, chunk.getCapacity());
			});
			
			if(cluster.getConfig().logActions()) LogUtil.printTable("Action", "alloc",
			                                                        "Type", "Push",
			                                                        "Chunk", chunk);
			
			ticket.populate(chunk);
			return chunk;
		}
		
		@Override
		public boolean dealloc(Chunk chunk, IOList<ChunkPointer> freeChunks) throws IOException{
			try{
				var cluster=chunk.cluster;
				if(!cluster.isLastPhysical(chunk)) return false;
				
				Chunk prevFree=null;
				
				var chunkCache=cluster.chunkCache;
				var data      =cluster.getData();
				
				if(cluster.getConfig().logActions()) LogUtil.printTable("Action", "free",
				                                                        "Type", "freeing",
				                                                        "Chunk", chunk);
				
				var endPtr=chunk.getPtr();
				chunkCache.remove(endPtr);
				data.setCapacity(endPtr.getValue());
				
				cluster.validate();

//				int prevIndex=freeChunks.find(ptr->ptr!=null&&endPtr.equals(cluster.getChunk(ptr).dataEnd()));
//				if(prevIndex!=-1){
//					var ch=cluster.getChunk(freeChunks.getElement(prevIndex));
//					freeChunks.removeElement(prevIndex);
//					if(!cluster.isLastPhysical(ch)) cluster.free(ch);
//					else dealloc(chunk, freeChunks);
//				}
				
				return true;
			}catch(Throwable e){
				throw new IOException("Failed to pop "+chunk, e);
			}
		}
	}
	
	private static final class ListingAddConsume extends MAllocer{
		
		@Override
		public Chunk alloc(Cluster cluster, IOList<ChunkPointer> freeChunks, AllocateTicket ticket) throws IOException{
			
			int minChunkSize=cluster.getMinChunkSize();
			
			if(cluster.isSafeMode()) return null;
			if(freeChunks.noneMatches(Objects::nonNull)) return null;
			
			UnsafeFunction<Chunk, Chunk, IOException> popEnd=chunk->{
				Chunk chunkUse=new Chunk(cluster, chunk.getPtr(), ticket.bytes(), calcPtrSize(cluster, ticket));
				chunkUse.setLocation(new ChunkPointer(chunk.dataEnd()-chunkUse.totalSize()));
				chunkUse.setIsUserData(ticket.userData()!=null);
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
				
				if(cap<ticket.bytes()){
					continue;
				}
				
				if(!cluster.isSafeMode()){
					long maxSize =ticket.bytes()+minChunkSize;
					long overAloc=maxSize-cap;
					if(overAloc>=0&&overAloc<bestOverAloc&&ticket.approve(chunk)){
						bestOverAloc=overAloc;
						bestIndex=i;
						if(overAloc==0) break;
					}
				}
				
				if(largest==null||largest.getCapacity()<cap){
					Chunk chunkUse=popEnd.apply(chunk);
					
					long freeCapacity=chunk.getCapacity()-chunkUse.totalSize();
					if(freeCapacity>=minChunkSize&&ticket.approve(chunk)){
						largest=chunk;
					}
				}
			}
			
			if(bestIndex!=-1){
				Chunk chunk=cluster.getChunk(freeChunks.getElement(bestIndex));
				
				freeChunks.setElement(bestIndex, null);
				chunk.modifyAndSave(c->{
					c.setIsUserData(ticket.userData()!=null);
					c.setUsed(true);
				});
				
				if(cluster.getConfig().logActions()) LogUtil.printTable("Action", "alloc",
				                                                        "Type", "reuse exact",
				                                                        "Chunk", chunk);
				ticket.populate(chunk);
				return chunk;
			}
			
			if(largest!=null){
				Chunk chunkUse=makeChunkReal(popEnd.apply(largest));
				
				long freeCapacity=largest.getCapacity()-chunkUse.totalSize();
				assert freeCapacity>=minChunkSize;
				
				largest.modifyAndSave(c->c.setCapacityConfident(freeCapacity));
				
				if(cluster.getConfig().logActions()) LogUtil.printTable("Action", "alloc",
				                                                        "Type", "reuse split",
				                                                        "Source", largest,
				                                                        "Chunk", chunkUse);
				
				ticket.populate(chunkUse);
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
			
			UnsafeConsumer<Chunk, IOException> clear=chunk->chunk.modifyAndSave(ch->{
				ch.setSize(0);
				ch.clearNextPtr();
			});
			
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
					if(cluster.getConfig().logActions()) LogUtil.printTable("Action", "free",
					                                                        "Type", "triple merge",
					                                                        "Source", prev,
					                                                        "Chunk", toFree,
					                                                        "Chunk 2", next);
					freeChunks.setElement(nextIndex, null);
					
					long start=prev.getCapacity();
					
					merge(chunkCache, prev, next);
					removeCache(chunkCache, toFree);
					
					long end=prev.getCapacity();
					if(cluster.getConfig().clearFreeData()){
						prev.zeroOutFromTo(start, end);
					}
				}else{
					if(cluster.getConfig().logActions()) LogUtil.printTable("Action", "free",
					                                                        "Type", "merge",
					                                                        "Source", toFree,
					                                                        "Chunk", next);
					
					clear.accept(toFree);
					if(cluster.getConfig().clearFreeData()){
						toFree.zeroOutCapacity();
					}
					freeChunks.setElement(nextIndex, toFree.getPtr());
					merge(chunkCache, toFree, next);
				}
			}else{
				clear.accept(toFree);
				if(cluster.getConfig().clearFreeData()){
					toFree.zeroOutCapacity();
				}
				if(prev!=null){
					if(cluster.getConfig().logActions()) LogUtil.printTable("Action", "free",
					                                                        "Type", "merge",
					                                                        "Source", prev,
					                                                        "Chunk", toFree);
					merge(chunkCache, prev, toFree);
				}else{
					if(cluster.getConfig().logActions()) LogUtil.printTable("Action", "free",
					                                                        "Type", "list",
					                                                        "Chunk", toFree);
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
		public Chunk alloc(Cluster cluster, IOList<ChunkPointer> freeChunks, AllocateTicket ticket) throws IOException{
			Chunk chunk;
			
			chunk=FREE_ADD_CONSUME.alloc(cluster, freeChunks, ticket);
			
			if(chunk==null){
				chunk=PUSH_POP.alloc(cluster, freeChunks, ticket);
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
	
	protected void removeCache(Map<ChunkPointer, Chunk> chunkCache, Chunk chunk) throws IOException{
		chunkCache.remove(chunk.getPtr());
	}
	
	protected void destroyChunk(Map<ChunkPointer, Chunk> chunkCache, Chunk chunk) throws IOException{
		removeCache(chunkCache, chunk);
		if(chunk.cluster.getConfig().clearFreeData()){
			chunk.zeroOutHead();
		}
	}
	
	protected NumberSize calcPtrSize(Cluster cluster, AllocateTicket ticket) throws IOException{
		return cluster.calcPtrSize(ticket.disableResizing());
	}
	
	public abstract Chunk alloc(Cluster cluster, IOList<ChunkPointer> freeChunks, AllocateTicket ticket) throws IOException;
	
	public abstract boolean dealloc(Chunk chunk, IOList<ChunkPointer> freeChunks) throws IOException;
	
}
