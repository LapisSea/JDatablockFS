package com.lapissea.fsf;

import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Internal class that maps file IOInterface to read and expand (when needed) a chunk chain
 */
@SuppressWarnings("AutoBoxing")
public class ChunkIO implements IOInterface{
	
	private final List<Chunk> chunks=new ArrayList<>(4);
	private final List<Long>  ends  =new ArrayList<>(4);
	
	private final IOInterface chunkSource;
	
	public ChunkIO(Chunk chunk){
		chunkSource=chunk.header.source;
		chunks.add(chunk);
		ends.add(chunk.getDataSize());
	}
	
	public Chunk getRoot(){
		return chunks.get(0);
	}
	
	private void addChunk(Chunk chunk){
		chunks.add(chunk);
		ends.add(ends.get(ends.size()-1)+chunk.getDataSize());
	}
	
	private void growChunks(int size) throws IOException{
		var last=chunks.get(chunks.size()-1);
		
		if(!discoverChunk()){
			var oldSize=last.getDataSize();
			last.header.requestMemory(last, size);
			if(oldSize!=last.getDataSize()){
				var prevOff=0L;
				if(ends.size()>1) prevOff=ends.get(ends.size()-2);
				
				ends.set(ends.size()-1, prevOff+last.getDataSize());
			}else{
				growChunks(size);
			}
		}
	}
	
	private boolean discoverChunk() throws IOException{
		var last=chunks.get(chunks.size()-1);
		
		if(!last.hasNext()) return false;
		
		addChunk(last.nextChunk());
		return true;
	}
	
	private long[] calcStartOffset(long fileOffset) throws IOException{
		int  startIndex;
		long startOffset;
		find:
		{
			for(int i=0;i<ends.size();i++){
				var remaining=ends.get(i)-fileOffset;
				if(remaining>0){
					startIndex=i;
					startOffset=chunks.get(startIndex).getDataSize()-remaining;
					break find;
				}
			}
			
			if(discoverChunk()) return calcStartOffset(fileOffset);
			
			throw new EOFException(fileOffset+" "+TextUtil.toNamedPrettyJson(chunks, true));
		}
		
		return new long[]{startIndex, startOffset};
	}
	
	@Override
	public RandomIO doRandom(){
		return new RandomIO(){
			@Override
			public RandomIO setPos(long pos) throws IOException{
				throw NotImplementedException.infer();//TODO: implement .setPos()
			}
			
			@Override
			public long getPos() throws IOException{
				throw NotImplementedException.infer();//TODO: implement .getPos()
			}
			
			@Override
			public long getSize() throws IOException{
				throw NotImplementedException.infer();//TODO: implement .getSize()
			}
			
			@Override
			public RandomIO setSize(long newSize) throws IOException{
				throw NotImplementedException.infer();//TODO: implement .setSize()
			}
			
			@Override
			public void close() throws IOException{
				throw NotImplementedException.infer();//TODO: implement .close()
			}
			
			@Override
			public void flush() throws IOException{
				throw NotImplementedException.infer();//TODO: implement .flush()
			}
			
			@Override
			public int read() throws IOException{
				throw NotImplementedException.infer();//TODO: implement .read()
			}
			
			@Override
			public int read(byte[] b, int off, int len) throws IOException{
				throw NotImplementedException.infer();//TODO: implement .read()
			}
			
			@Override
			public void write(int b) throws IOException{
				throw NotImplementedException.infer();//TODO: implement .write()
			}
			
			@Override
			public void write(byte[] b, int off, int len) throws IOException{
				throw NotImplementedException.infer();//TODO: implement .write()
			}
		};
	}
	
	@Override
	public ContentOutputStream write(long fileOffset) throws IOException{
		long[] offs=calcStartOffset(fileOffset);
		
		int  startIndex =(int)offs[0];
		long startOffset=offs[1];
		
		return new ContentOutputStream(){
			int index=startIndex;
			long offset=startOffset;
			Chunk chunk=chunks.get(index);
			ContentOutputStream chunkData=chunkSource.write(chunk.getDataStart()+startOffset);
			
			void ensureChunk(int size) throws IOException{
				if(remaining()!=0) return;
				
				var lastIndex=chunks.size()-1;
				if(lastIndex==index){//ran out of chunks
					growChunks(size);
					
					ensureChunk(size);
					return;
				}
				
				
				index++;
				
				chunk.syncHeader();
				chunk=chunks.get(index);
				chunk.setUsed(0);
				chunkData.close();
				chunkData=chunkSource.write(chunk.getDataStart());
				
				offset=0;
			}
			
			long remaining(){
				return chunk.getDataSize()-offset;
			}
			
			void logWrittenBytes(int numOBytes){
				offset+=numOBytes;
				chunk.pushUsed(offset);
			}
			
			@Override
			public void write(int b) throws IOException{
				ensureChunk(1);
				
				logWrittenBytes(1);
				chunkData.write(b);
			}
			
			@Override
			public void write(@NotNull byte[] b, int off, int len) throws IOException{
				while(len>0){
					ensureChunk(len);
					
					int toWrite=(int)Math.min(remaining(), len);
					
					logWrittenBytes(toWrite);
					chunkData.write(b, off, toWrite);
					
					
					len-=toWrite;
					off+=toWrite;
				}
				
			}
			
			@Override
			public void flush() throws IOException{
				chunkData.flush();
			}
			
			@Override
			public void close() throws IOException{
				chunkData.close();
				chunk.setUsed(offset);
				chunk.chainForwardFree();
				
				if(index+1<chunks.size()){
					chunks.subList(index+1, chunks.size()).clear();
				}
				chunk.syncHeader();
			}
			
			@Override
			public String toString(){
				return "Output{"+
				       "index="+index+
				       ", chunk="+TextUtil.toString(chunk)+
				       ", offset="+offset+
				       '}';
			}
		};
	}
	
	@Override
	public ContentInputStream read(long fileOffset) throws IOException{
		long[] offs=calcStartOffset(fileOffset);
		
		int  startIndex =(int)offs[0];
		long startOffset=offs[1];
		
		return new ContentInputStream(){
			int index=startIndex;
			long offset=startOffset;
			Chunk chunk=chunks.get(index);
			ContentInputStream chunkData=chunkSource.read(chunk.getDataStart()+startOffset);
			
			//returns if end is reached
			boolean ensureChunk() throws IOException{
				if(remaining()!=0) return false;
				
				var lastIndex=chunks.size()-1;
				if(lastIndex==index){//ran out of chunks
					var last=chunks.get(chunks.size()-1);
					
					if(!last.hasNext()) return true;
					
					addChunk(last.nextChunk());
					return ensureChunk();
					
				}
				
				index++;
				chunk=chunks.get(index);
				chunkData=chunkSource.read(chunk.getDataStart());
				offset=0;
				return false;
			}
			
			long remaining(){
				return chunk.getUsed()-offset;
			}
			
			@Override
			public int read(@NotNull byte[] b, int off, int len) throws IOException{
				
				int sum=0;
				while(len>0){
					if(ensureChunk()){
						return sum==0?-1:sum;
					}
					var toRead=(int)Math.min(remaining(), len);
					var read  =chunkData.read(b, off, toRead);
					offset+=read;
					len-=read;
					off+=read;
					sum+=read;
				}
				return sum;
			}
			
			@Override
			public long skip(long n) throws IOException{
				if(ensureChunk()) return 0;
				
				var skipped=chunkData.skip(Math.min(n, remaining()));
				offset+=skipped;
				return skipped;
			}
			
			@Override
			public int available() throws IOException{
				return (int)Math.min(chunkData.available(), remaining());
			}
			
			@Override
			public int read() throws IOException{
				if(ensureChunk()) return -1;
				
				offset++;
				return chunkData.read();
			}
			
			@Override
			public void close() throws IOException{
				chunkData.close();
			}
		};
	}
	
	@Override
	public long size() throws IOException{
		//noinspection StatementWithEmptyBody
		while(discoverChunk()) ;
		
		if(chunks.size()==1) return chunks.get(0).getUsed();
		
		return ends.get(ends.size()-2)+
		       chunks.get(ends.size()-1).getUsed();
	}
	
	@Override
	public void setSize(long newSize) throws IOException{
		write(newSize).close();
	}
}
