package com.lapissea.fsf;

import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.BiIntConsumer;
import com.lapissea.util.function.TriFunction;

import java.awt.*;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

public class FileSystemInFile{
	
	/**
	 * How much bytes the file table will allocate as free space for expansion to delay or avoid file table fragmentation.<br></br>
	 * Used when creating a new file or on defragmentation.
	 */
	public static int FILE_TABLE_PADDING =30;
	public static int MINIMUM_CHUNK_SIZE =16;
	public static int FREE_CHUNK_CAPACITY=2;
	
	public static final boolean DEBUG_VALIDATION=true;
	
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
			pointer=header.getByPath(path);
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
		return renderFile(minWidth, minHeight, ids, 4);
	}
	
	public BufferedImage renderFile(int minWidth, int minHeight, long[] ids, int resolutionMul) throws IOException{
		var       size=header.source.getSize();
		final int width;
		final int height;
		{
			int w=minWidth;
			int h=minHeight;
			
			while(w*h<size){
				if(w<=h) w++;
				else h++;
			}
			width=w;
			height=h;
		}
		
		int pixelSize=3*resolutionMul;
		
		BufferedImage img=new BufferedImage(width*pixelSize, height*pixelSize, BufferedImage.TYPE_INT_ARGB);
		Graphics2D    g2 =img.createGraphics();
		g2.setFont(new Font("Monospaced", Font.PLAIN, pixelSize));
		
		
		g2.setStroke(new BasicStroke(pixelSize/6F));
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		
		var headerData=header.headerStartChunks()
		                     .stream()
		                     .flatMap(c->{
			                     try{
				                     return c.loadWholeChain().stream();
			                     }catch(IOException e){
				                     throw UtilL.uncheckedThrow(e);
			                     }
		                     })
		                     .collect(Collectors.toList());
		
		BiFunction<Color, Float, Color> mul=(col, val)->new Color((int)(col.getRed()*val), (int)(col.getGreen()*val), (int)(col.getBlue()*val));
		TriFunction<Color, Color, Float, Color> mix=(l, r, val)->{
			var rev=1-val;
			return new Color(
				(l.getRed()/255F)*val+(r.getRed()/255F)*rev,
				(l.getGreen()/255F)*val+(r.getGreen()/255F)*rev,
				(l.getBlue()/255F)*val+(r.getBlue()/255F)*rev,
				(l.getAlpha()/255F)*val+(r.getAlpha()/255F)*rev
			);
		};
		
		int[] counter={0};
		
		BiIntConsumer<Color> pixelPushByte=(b, color)->{
			var count=counter[0];
			int x=count%width,
				y=count/width;
			
			for(int i=0;i<9;i++){
				Color col=color;
				int   pp =pixelSize/3;
				
				int x1=x*pixelSize+(i%3)*pp;
				int y1=y*pixelSize+(i/3)*pp;
				
				if(i==8){
					f:
					{
						for(long id : ids){
							if(id==counter[0]){
								g2.setColor(Color.YELLOW);
								break f;
							}
						}
						g2.setColor(Color.BLACK);
					}
					g2.fillRect(x1, y1, pp, pp);
					continue;
				}
				
				if((b&(1<<i))==0) col=mul.apply(col, 1F/4);
				
				g2.setColor(col);
				g2.fillRect(x1, y1, pp, pp);
			}
		};
		BiIntConsumer<Color> pixelPushLetter=(b, color)->{
			char c    =(char)b;
			var  count=counter[0];
			int x=count%width,
				y=count/width;
			
			x*=pixelSize;
			y*=pixelSize;

//			g.setColor(new Color(1, 1, 1, 0.3F));
//			g.fillRect(x, y, pixelSize, pixelSize);
			
			x+=pixelSize*0.2F;
			y+=pixelSize*0.8F;
			
			g2.setColor(color);
			g2.drawString(""+c, x, y);
			g2.setColor(new Color(1, 1, 1, 0.4F));
			g2.drawString(""+c, x, y);
		};
		
		BiIntConsumer<Color> pixelPush=(b, color)->{
			
			pixelPushByte.accept(b, color);
			
			char c=(char)b;
			if(g2.getFont().canDisplay(c)&&!Character.isWhitespace(c)){
				pixelPushLetter.accept(c, color);
			}
			
			counter[0]++;
		};
		
		Function<Chunk, Color> randColor;
		{
			Random rand=new Random();
			randColor=chunk->{
				rand.setSeed(chunk.getOffset());
				
				var c=new Color(Color.HSBtoRGB(rand.nextFloat()*255, 1, 1));
				return new Color(c.getRed()/255F, c.getGreen()/255F, c.getBlue()/255F, 0.3F);
			};
		}
		
		for(long i=0;i<size;i++){
			pixelPush.accept(0, Color.BLACK);
		}
		
		counter[0]=0;
		
		for(byte b : Header.getMagicBytes()){
			pixelPush.accept(b, Color.RED);
		}
		pixelPushByte.accept(header.version.major, Color.RED);
		counter[0]++;
		pixelPushByte.accept(header.version.minor, Color.RED);
		counter[0]++;
		
		var chunks=header.allChunks(true);
		
		try(var in=header.source.doRandom()){
			List<List<Chunk>> chains=new ArrayList<>();
			
			while(!chunks.isEmpty()){
				var chain=chunks.get(0).loadWholeChain();
				chunks.removeAll(chain);
				chains.add(chain);
			}
			
			for(var chain : chains){
				var rand=randColor.apply(chain.get(0));
				
				for(Chunk chunk : chain){
					
					in.setPos(chunk.getOffset());
					counter[0]=(int)chunk.getOffset();
					
					Color bodyCol;
					if(chunk.isChunkUsed()){
						if(headerData.contains(chunk)) bodyCol=Color.BLUE;
						else{
							bodyCol=Color.GREEN.darker();
						}
					}else bodyCol=Color.GRAY;
					
					Color headCol=mix.apply(new Color(Math.min(255, bodyCol.getRed()+100), Math.min(255, bodyCol.getGreen()+100), Math.min(255, bodyCol.getBlue()+100)), rand, 0.7F);
					
					for(long i=0, j=chunk.getHeaderSize();i<j;i++){
						pixelPushByte.accept(in.read(), headCol);
						counter[0]++;
					}
					
					for(long i=0, j=chunk.getDataCapacity();i<j;i++){
						if(i >= chunk.getUsed()) bodyCol=Color.LIGHT_GRAY;
						pixelPush.accept(in.read(), mul.apply(bodyCol, ((chunk.getDataCapacity()-i)/(float)chunk.getDataCapacity())*0.7F+0.3F));
					}
				}
			}
			for(var chain : chains){
				
				var col=randColor.apply(chain.get(0));
				
				for(Chunk chunk : chain){
					if(!chunk.hasNext()) continue;
					
					in.setPos(chunk.getOffset());
					counter[0]=(int)chunk.getOffset();
					
					g2.setColor(col);
					var v1=chunk.getOffset();
					var v2=chunk.getNext();
					
					int x1=(int)(v1%width),
						y1=(int)(v1/width);
					
					int x2=(int)(v2%width),
						y2=(int)(v2/width);
					
					g2.draw(new Line2D.Double((x1+0.5)*pixelSize, (y1+0.5)*pixelSize, (x2+0.5)*pixelSize, (y2+0.5)*pixelSize));
				}
			}
			
		}
		g2.dispose();
		return img;
	}
}
