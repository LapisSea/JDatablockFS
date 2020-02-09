package com.lapissea.fsf;

import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.fsf.headermodule.HeaderModule;
import com.lapissea.fsf.io.IOInterface;
import com.lapissea.util.*;
import com.lapissea.util.function.BiIntConsumer;
import com.lapissea.util.function.TriConsumer;
import com.lapissea.util.function.TriFunction;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;

import static com.lapissea.util.UtilL.*;
import static java.awt.Font.*;

@SuppressWarnings("AutoBoxing")
public class FileSystemInFile{
	
	public static final boolean DEBUG_VALIDATION=true;
	
	public static class Config{
		
		public enum CacheMode{
			/**
			 * Most aggressive caching. Never drops any object no matter what. (useful for debugging)
			 */
			AGGRESSIVE,
			/**
			 * Aggressive ({@link SoftReference}) caching. Will drop objects if system is running low on memory (useful for very slow IO such as over the network)
			 */
			FORGIVING,
			/**
			 * Smart ({@link WeakReference}) conservative caching. Will drop any object if it is no longer used but will will keep it conserve if for a minimum amount of period. (useful for data that is accessed many times by disconnected modules)
			 */
			LAZY,
			/**
			 * Smart ({@link WeakReference}) caching. Will drop any object if it is no longer used. (Useful for most general purpose)
			 */
			WEAK,
		}
		
		/**
		 * Hint at what should be used to mark a file system when storing it.
		 */
		public static String    DEFAULT_EXTENSION               ="fsf";
		/**
		 * Defines if DEFAULT_EXTENSION should be taken as an enforced rule rather than a hint.
		 */
		public static boolean   DEFAULT_SHOULD_ENFORCE_EXTENSION=true;
		/**
		 * Used to define a memory buffer limit when writing to a new file. Used to reduce fragmentation.
		 */
		public static int       DEFAULT_MAX_BUFFERING_INIT_SIZE =1024;
		/**
		 * How much bytes the file table will allocate as free space for expansion to delay or avoid file table fragmentation.<br></br>
		 * Used when creating a new file or on defragmentation.
		 */
		public static int       DEFAULT_FILE_TABLE_PADDING      =3;
		/**
		 * Defines the initial size of a chunk. (Note that this is a rule only for the chunk allocation circumstances where allocation is not easy or signs of fragmentation are apparent)
		 */
		public static int       DEFAULT_MINIMUM_CHUNK_SIZE      =16;
		/**
		 * Defines how much free chunks can be kept track of before fragmenting the list.
		 */
		public static int       DEFAULT_FREE_CHUNK_CAPACITY     =2;
		/**
		 * Defines a memory caching policy to reduce repeated reads and deserialization. This policy is enforced within a {@link Map} implementation used by various systems. <br><br>
		 * Rules:
		 * <ul>
		 *     <li>If a {@link Map} returns <code>null</code> for a particular <code>long offset</code> then a value is not cached and must be read and parsed.</li>
		 *
		 *     <li>If a {@link Map} returns <code>non null</code> then this is object when serialized must produce the same bytes in values and length as
		 *     the bytes under the offset in the underlying {@link IOInterface} (<code>offset</code> local to a sub system such as a list or global, depending on the context)</li>
		 *
		 *     <li><b>ALL</b> cached objects <b>MUST</b> be kept in synchronisation with the underlying {@link IOInterface} (by modification of existing
		 *     objects not replacement in order to keep existing references in sync) with an exception of outside modifications what is illegal as outside
		 *     changes are not checked for and may cause corruption.</li>
		 * </ul>
		 */
		public static CacheMode DEFAULT_CACHE_MODE              =DEBUG_VALIDATION?CacheMode.AGGRESSIVE:CacheMode.WEAK;
		
		
		public final int       fileTablePadding;
		public final int       minimumChunkSize;
		public final int       freeChunkCapacity;
		public final int       maxBufferingInitSize;
		public final CacheMode cacheMode;
		public final String    extension;
		public final boolean   shouldEnforceExtension;
		
		public Config(){
			this(DEFAULT_FILE_TABLE_PADDING,
			     DEFAULT_MINIMUM_CHUNK_SIZE,
			     DEFAULT_FREE_CHUNK_CAPACITY,
			     DEFAULT_MAX_BUFFERING_INIT_SIZE,
			     DEFAULT_CACHE_MODE,
			     DEFAULT_EXTENSION,
			     DEFAULT_SHOULD_ENFORCE_EXTENSION);
		}
		
		public Config(int fileTablePadding, int minimumChunkSize, int freeChunkCapacity, int maxBufferingInitSize, CacheMode cacheMode, String extension, boolean shouldEnforceExtension){
			this.fileTablePadding=fileTablePadding;
			this.minimumChunkSize=minimumChunkSize;
			this.freeChunkCapacity=freeChunkCapacity;
			this.maxBufferingInitSize=maxBufferingInitSize;
			this.cacheMode=cacheMode;
			this.extension=extension;
			this.shouldEnforceExtension=shouldEnforceExtension;
		}
		
		public <K, V> Map<K, V> newCacheMap(){
			return switch(cacheMode){
				case AGGRESSIVE -> new HashMap<>();
				case FORGIVING -> new SoftValueHashMap<>();
				case LAZY -> new WeakValueHashMap<K, V>().defineStayAlivePolicy(3);
				case WEAK -> new WeakValueHashMap<>();
			};
		}
		
		public File enforceExtension(File file){
			var extp="."+extension;
			
			if(file.getPath().endsWith(extp)) return file;
			return new File(file.getPath()+extp);
		}
	}
	
	public final Header header;
	
	public FileSystemInFile(File file) throws IOException{
		this(file, new Config());
	}
	
	public FileSystemInFile(File file, Config config) throws IOException{
		this(new IOInterface.FileRA(file, config), config);
	}
	
	public FileSystemInFile(IOInterface source) throws IOException{
		this(source, new Config());
	}
	
	public FileSystemInFile(IOInterface source, Config config) throws IOException{
		header=new Header(source, config);
	}
	
	public VirtualFile getFile(String path) throws IOException{
		path=Header.normalizePath(path);
		
		var pointer=header.getByPath(path);
		if(pointer==null){
			pointer=new FilePointer(header, path);
		}
		
		return new VirtualFile(pointer);
	}
	
	public VirtualFile createFile(String path) throws IOException{
		return createFile(path, 0);
	}
	
	/**
	 * Same as {@link #getFile(String) getFile} except it immediately defines and pre allocates space for a file if it does not already exist. (useful when create
	 */
	public VirtualFile createFile(String path, long initialSize) throws IOException{
		path=Header.normalizePath(path);
		
		var pointer=header.getByPath(path);
		if(pointer==null){
			header.createFile(path);
			if(initialSize>0) header.makeFileData(path, initialSize);
			pointer=header.getByPath(path);
		}
		
		return new VirtualFile(pointer);
	}
	
	public void delete(String path) throws IOException{
		getFile(path).delete();
	}
	
	public VirtualFile[] listFiles() throws IOException{
		return header.listFiles();
	}
	
	public void defragment() throws IOException{
		header.defragment();
	}
	
	
	private interface GetAlpha{
		int get(int x, int y);
	}
	
	public BufferedImage renderFile(int width, long[] ids, int resolutionMul, boolean printError) throws IOException{
		var size=header.source.getSize();
		int w   =Math.max(width, 1);
		int h   =1;
		
		while(w*h<size){
			h++;
		}
		
		return renderFile(w, h, ids, resolutionMul, printError);
	}
	
	public BufferedImage renderFile(int minWidth, int minHeight, long[] ids, int resolutionMul, boolean printError) throws IOException{
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
		
		int pixelSize=3*resolutionMul;
		
		BufferedImage img=new BufferedImage(width*pixelSize, height*pixelSize, BufferedImage.TYPE_INT_ARGB);
		Graphics2D    g2 =img.createGraphics();
		
		g2.setFont(new Font(MONOSPACED, PLAIN, pixelSize));
		
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		
		int[]  counter={0};
		BitSet filled =new BitSet(Math.toIntExact(size));
		
		BiIntConsumer<Color> pixelPushByte=(b, color)->{
			var count=counter[0];
			if(count<filled.size()){
				
				if(filled.get(count)){
					color=Color.RED;
				}
				filled.set(count, true);
			}
			
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
				
				if((b&(1<<i))==0) col=mul.apply(col, 1F/3);
				
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
		};
		
		Function<Long, Color>    randColor;
		Function<Long, double[]> randPos;
		Function<Long, Double>   randVal;
		{
			Random rand=new Random();
			randPos=chunkStart->{
				rand.setSeed(chunkStart);
				
				return new double[]{rand.nextDouble()-0.5, rand.nextDouble()-0.5};
			};
			randVal=chunkStart->{
				rand.setSeed(chunkStart);
				return rand.nextDouble();
			};
			randColor=chunkStart->{
				rand.setSeed(chunkStart);
				
				var c=new Color(Color.HSBtoRGB(rand.nextFloat()*255, 1, 1));
				return new Color(c.getRed()/255F, c.getGreen()/255F, c.getBlue()/255F, 0.5F);
			};
		}
		
		Color oufColor=new Color(255, 0, 255);
		
		BufferedImage lineImgOutline=null;
		try{
			var allChunks=header.allChunks(true);
			
			try(var in=header.source.doRandom()){
				for(int i=0;i<Header.FILE_HEADER_SIZE;i++){
					pixelPush.accept(in.read(), Color.RED);
					counter[0]++;
				}
				
				var drawn=new HashSet<Long>();
				
				for(Map.Entry<HeaderModule, List<List<ChunkLink>>> entry : allChunks.entrySet()){
					HeaderModule          module=entry.getKey();
					List<List<ChunkLink>> chains=entry.getValue();
					
					var ownsBinaryOnly=module.ownsBinaryOnly();
					
					for(var chain : chains){
						var rand=randColor.apply(chain.get(0).sourcePos);
						
						var l     =chain.get(0);
						var owning=l.sourceValidChunk&&module.getOwning().contains(l.dereferenceSource(header));
						
						for(var link : chain){
							if(!link.sourceValidChunk) continue;
							if(!drawn.add(link.sourcePos)) continue;
							
							Chunk chunk=link.dereferenceSource(header);
							
							in.setPos(chunk.getOffset());
							counter[0]=(int)chunk.getOffset();
							
							Color bodyCol=module.displayColor();
							if(owning) bodyCol=mix.apply(bodyCol, Color.RED, 0.7F);
							
							Color headCol=mix.apply(new Color(Math.min(255, bodyCol.getRed()+100), Math.min(255, bodyCol.getGreen()+100), Math.min(255, bodyCol.getBlue()+100)), rand, 0.7F);
							
							for(long i=0, j=chunk.getHeaderSize();i<j;i++){
								
								var b=in.read();
								if(b==-1) pixelPushByte.accept(255, oufColor);
								else pixelPushByte.accept(b, headCol);
								counter[0]++;
							}
							
							
							for(long i=0, j=chunk.getCapacity();i<j;i++){
								Color pixelCol=bodyCol;
								if(i >= chunk.getSize()){
									pixelCol=mul.apply(pixelCol, 0.3F);
									
									var siz=chunk.getCapacity()-chunk.getSize();
								}else{
									var siz=chunk.getCapacity();
								}
								
								var b=in.read();
								if(b==-1) pixelPushByte.accept(255, oufColor);
								else (owning&&ownsBinaryOnly?pixelPushByte:pixelPush).accept(b, pixelCol);
								counter[0]++;
							}
						}
					}
				}
				
				
				BufferedImage lineImg=new BufferedImage(width*pixelSize, height*pixelSize, BufferedImage.TYPE_INT_ARGB);
				Graphics2D    lineG  =lineImg.createGraphics();
				
				lineG.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				float lineWidth=Math.max(1F/pixelSize, 1/15F);
				lineG.setStroke(new BasicStroke(lineWidth));
				lineG.scale(pixelSize, pixelSize);
				
				Consumer<double[]> drawLine=cords->lineG.draw(new Line2D.Double(cords[0], cords[1], cords[2], cords[3]));
				
				TriConsumer<Color, Long, Long> drawLink=(col, v1, v2)->{
					lineG.setColor(col);
					var padding=0.2;
					var rp1    =randPos.apply(v1);
					var rp2    =randPos.apply(v2);
					
					double x1c=(v1%width)+0.5;
					double y1c=(long)(v1/width)+0.5;
					
					double x2c=(v2%width)+0.5;
					double y2c=(long)(v2/width)+0.5;
					
					double x1=x1c+rp1[0]*(1-padding*2);
					double y1=y1c+rp1[1]*(1-padding*2);
					
					double x2=x2c+padding+rp2[0]*(1-padding*2);
					double y2=y2c+padding+rp2[1]*(1-padding*2);
					
					double xMid=(x1+x2)/2;
					double yMid=(y1+y2)/2;
					
					double x2m1=x2-x1;
					double y2m1=y2-y1;
					
					
					double normal =-Math.atan2(y2m1, x2m1);
					double tangent=normal+Math.PI/2;
					
					double arrowSize=Math.min(0.5, Math.sqrt(y2m1*y2m1+x2m1*x2m1)/3)/2;
					
					double nsin=Math.sin(normal)*arrowSize;
					double ncos=Math.cos(normal)*arrowSize;
					double tsin=Math.sin(tangent)*arrowSize;
					double tcos=Math.cos(tangent)*arrowSize;
					
					drawLine.accept(new double[]{x1, y1, x2, y2});
					
					drawLine.accept(new double[]{xMid+tsin, yMid+tcos, xMid+nsin-tsin, yMid+ncos-tcos});
					drawLine.accept(new double[]{xMid+tsin, yMid+tcos, xMid-nsin-tsin, yMid-ncos-tcos});
					
					var circleSiz=3*lineWidth;
					lineG.fill(new Ellipse2D.Double(x1c-circleSiz/2, y1c-circleSiz/2, circleSiz, circleSiz));
					
					drawLine.accept(new double[]{x1, y1, x1c, y1c});
					drawLine.accept(new double[]{x2, y2, x2c, y2c});
				};
				
				
				for(Map.Entry<HeaderModule, List<List<ChunkLink>>> entry : allChunks.entrySet()){
					HeaderModule          module=entry.getKey();
					List<List<ChunkLink>> chains=entry.getValue();
					
					for(ChunkLink reference : module.buildReferences()){
						var val =reference.getPointer().getValue();
						var rand=randColor.apply(val);
						drawLink.accept(rand, reference.sourcePos, val);
					}
					
					for(List<ChunkLink> chain : chains){
						var col=new Color(randColor.apply(chain.get(0).sourcePos).getRGB());
						
						for(ChunkLink link : chain){
							if(!link.hasPointer()) continue;
							drawLink.accept(col, link.sourcePos, link.getPointer().getValue());
						}
					}
				}
				
				
				lineG.dispose();
				
				lineImgOutline=new BufferedImage(lineImg.getWidth(), lineImg.getHeight(), BufferedImage.TYPE_INT_ARGB);
				
				var outlineColor=new Color(1, 1, 1, 0F).getRGB();
				
				
				int[] pixels=lineImg.getRGB(0, 0, lineImg.getWidth(), lineImg.getHeight(), null, 0, lineImg.getWidth());
				for(int i=0, j=pixels.length;i<j;i++){
					pixels[i]=((pixels[i] >> 24)&0xff);
				}
				
				GetAlpha getAlpha=(x, y)->pixels[x+y*lineImg.getWidth()];
				
				int perChunk=256;
				
				int num=lineImg.getWidth()*lineImg.getHeight();
				
				BufferedImage finalLineImgOutline=lineImgOutline;
				IntStream.range(0, num/perChunk).parallel().forEach(idC->{
					for(int id=idC*perChunk, j=Math.min(num, (idC+1)*perChunk);id<j;id++){
						
						int x=id%lineImg.getWidth();
						int y=id/lineImg.getWidth();
						int alpha;
						
						var xLower=x>0;
						var xUpper=x<lineImg.getWidth()-1;
						var yLower=y>0;
						var yUpper=y<lineImg.getHeight()-1;
						
						alpha=getAlpha.get(x, y);
						
						if(xLower&&alpha<0xFF) alpha=Math.max(alpha, getAlpha.get(x-1, y));
						if(xUpper&&alpha<0xFF) alpha=Math.max(alpha, getAlpha.get(x+1, y));
						if(yLower&&alpha<0xFF) alpha=Math.max(alpha, getAlpha.get(x, y-1));
						if(yUpper&&alpha<0xFF) alpha=Math.max(alpha, getAlpha.get(x, y+1));
						if(yUpper&&alpha<0xFF) alpha=Math.max(alpha, getAlpha.get(x, y+1));

//						if(xLower&&yLower&&alpha<0xFF) alpha=Math.max(alpha, getAlpha.get(x-1, y-1)/2);
//						if(xLower&&yUpper&&alpha<0xFF) alpha=Math.max(alpha, getAlpha.get(x-1, y+1)/2);
//						if(xUpper&&yUpper&&alpha<0xFF) alpha=Math.max(alpha, getAlpha.get(x+1, y+1)/2);
//						if(xUpper&&yLower&&alpha<0xFF) alpha=Math.max(alpha, getAlpha.get(x+1, y-1)/2);
						
						finalLineImgOutline.setRGB(x, y, (alpha<<24)|outlineColor);
						
					}
				});
				
				Graphics2D out=lineImgOutline.createGraphics();
				out.drawImage(lineImg, null, 0, 0);
				out.dispose();
				
			}
		}catch(Throwable e){
			e.printStackTrace();
			if(printError) e.printStackTrace();
		}
		
		try(var in=header.source.doRandom()){
			for(int i=0;i<size;i++){
				var b=in.readUnsignedInt1();
				if(!filled.get(i)){
					Assert(in.getPos()-1==i, in.getPos()-1, i);
					counter[0]=i;
					pixelPush.accept(b, Color.DARK_GRAY.darker());
				}
			}
		}
		if(lineImgOutline!=null){
			AlphaComposite ac=AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5F);
			g2.setComposite(ac);
			
			g2.drawImage(lineImgOutline, null, 0, 0);
		}
		g2.dispose();
		return img;
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof FileSystemInFile)) return false;
		FileSystemInFile that=(FileSystemInFile)o;
		return findContentDifference(that)==null;
	}
	
	public PairM<Chunk, Chunk> findContentDifference(FileSystemInFile that){
		
		Iterator<Chunk> i1, i2;
		try{
			i1=this.header.allChunkWalkerFlat(true);
			i2=that.header.allChunkWalkerFlat(true);
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
		
		while(i1.hasNext()&&i2.hasNext()){
			Chunk o1=i1.next();
			Chunk o2=i2.next();
			
			if(o1.isUsed()!=o2.isUsed()||o1.getOffset()!=o2.getOffset()||o1.getNext()!=o2.getNext()){
				return new PairM<>(o1, o2);
			}
			
			var io1=o1.io();
			var io2=o2.io();
			
			if(io1.getSize()!=io2.getSize()||io1.getCapacity()!=io2.getCapacity()){
				return new PairM<>(o1, o2);
			}
			
			if(o1.isUsed()){
				try{
					if(!Arrays.equals(io1.readAll(), io2.readAll())){
						return new PairM<>(o1, o2);
					}
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
		}
		
		if(!i1.hasNext()&&!i2.hasNext()) return null;
		
		return new PairM<>(i1.hasNext()?i1.next():null, i2.hasNext()?i2.next():null);
	}
	
	@Override
	public int hashCode(){
		return header.hashCode();
	}
}
