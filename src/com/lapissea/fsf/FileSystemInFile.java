package com.lapissea.fsf;

import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public class FileSystemInFile{
	
	/**
	 * How much bytes the file table will allocate as free space for expansion to delay or avoid file table fragmentation.<br></br>
	 * Used when creating a new file or on defragmentation.
	 */
	public static int FILE_TABLE_PADDING =30;
	public static int MINIMUM_CHUNK_SIZE =16;
	public static int FREE_CHUNK_CAPACITY=2;
	
	/**
	 * Used to define a memory buffer limit when writing to a new file. Used to reduce fragmentation.
	 */
	public static int MAX_BUFFERING_INIT_SIZE=1024;
	
	public static final String EXTENSION="fsf";
	
	
	public final Header header;
	
	public FileSystemInFile(File file) throws IOException{
		this(new IOInterface.FileRA(file));
	}
	
	public FileSystemInFile(IOInterface source) throws IOException{
		header=new Header(source);
	}
	
	public VirtualFile getFile(String path) throws IOException{
		path=Header.normalizePath(path);
		
		var pointer=header.getByPath(path);
		if(pointer==null){
			pointer=new FilePointer(header, path);
		}
		
		return new VirtualFile(pointer);
	}
	
	
	/**
	 * Same as {@link #getFile(String) getFile} except it immediately defines and pre allocates space for a file if it does not already exist. (useful when create
	 */
	public VirtualFile createFile(String path, long initialSize) throws IOException{
		path=Header.normalizePath(path);
		
		var pointer=header.getByPath(path);
		if(pointer==null){
			var chunk=header.createFile(path, initialSize);
			pointer=new FilePointer(header, path, chunk.getOffset());
		}
		
		return new VirtualFile(pointer);
	}
	
	public void delete(String path) throws IOException{
		LogUtil.println(TextUtil.toTable(header.allChunks(true)));
		System.exit(0);
		getFile(path).delete();
	}
	
	public VirtualFile[] listFiles() throws IOException{
		return header.listFiles();
	}
	
	public void defragment() throws IOException{
//		LogUtil.println(TextUtil.toTable(header.allChunks(true)));
//		LogUtil.println();
		header.defragment();
//		LogUtil.println(TextUtil.toTable(header.allChunks(true)));
//		LogUtil.println();
	}
	
	
	public BufferedImage renderFile(int minWidth, int minHeight, long[] ids) throws IOException{
		var size=header.source.getSize();
		int w   =minWidth;
		int h   =minHeight;
		
		while(w*h<size){
			if(w<=h) w++;
			else h++;
		}
		
		BufferedImage img=new BufferedImage(w*3, h*3, BufferedImage.TYPE_INT_ARGB);
		
		
		var headerData=header.headerChunks()
		                     .stream()
		                     .flatMap(c->{
			                     try{
				                     return c.makeChain().stream();
			                     }catch(IOException e){
				                     throw UtilL.uncheckedThrow(e);
			                     }
		                     })
		                     .collect(Collectors.toList());
		int[] counter={0};
		BiConsumer<Integer, Color> pixelPush=(bo, color)->{
			int x=counter[0]%(img.getWidth()/3), y=counter[0]/(img.getWidth()/3);
			int b=bo;
			
			for(int i=0;i<8;i++){
				Color col=color;
				int   x1 =x*3+i%3;
				int   y1 =y*3+i/3;
				
				for(long id : ids){
					if(id==counter[0]){
						col=Color.YELLOW;
						break;
					}
				}
				
				if((b&(1<<i))==0) col=new Color(col.getRed()/4, col.getGreen()/4, col.getBlue()/4);
				
				
				img.setRGB(x1, y1, col.getRGB());
			}
			img.setRGB(x*3+2, y*3+2, Color.BLACK.getRGB());
			
			counter[0]++;
		};
		
		for(long i=0;i<size;i++){
			pixelPush.accept(0, Color.BLACK);
		}
		
		counter[0]=0;
		
		for(byte b : Header.getMagicBytes()){
			pixelPush.accept((int)b, Color.RED);
		}
		pixelPush.accept((int)header.version.major, Color.RED);
		pixelPush.accept((int)header.version.minor, Color.RED);
		
		try(var in=header.source.doRandom()){
			for(Chunk chunk : header.allChunks(true)){
				in.setPos(chunk.getOffset());
				counter[0]=(int)chunk.getOffset();
				
				Color bodyCol=chunk.isChunkUsed()?headerData.contains(chunk)?Color.BLUE:Color.GREEN.darker():Color.GRAY;
				Color headCol=new Color(Math.min(255, bodyCol.getRed()+100), Math.min(255, bodyCol.getGreen()+100), Math.min(255, bodyCol.getBlue()+100));
				
				for(long i=0, j=chunk.getHeaderSize();i<j;i++){
					pixelPush.accept(in.read(), i==0?Color.ORANGE:headCol);
				}
				for(long i=0, j=chunk.getUsed();i<j;i++){
					pixelPush.accept(in.read(), bodyCol);
				}
				for(long i=0, j=chunk.getDataSize()-chunk.getUsed();i<j;i++){
					pixelPush.accept(in.read(), Color.GRAY);
				}
			}
		}
		
		return img;
	}
}
