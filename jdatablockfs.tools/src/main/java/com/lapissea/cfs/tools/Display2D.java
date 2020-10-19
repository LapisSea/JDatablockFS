package com.lapissea.cfs.tools;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.SelfPoint;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.Offset;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.cfs.objects.chunk.ObjectPointer;
import com.lapissea.util.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;

import static java.awt.RenderingHints.*;

@SuppressWarnings("AutoBoxing")
public class Display2D extends BinaryDrawing implements DataLogger{
	
	private Graphics2D currentGraphics;
	
	private record ByteInfo(int uVal, Color color, boolean withChar){}
	
	private class Pan extends JPanel{
		
		public int mouseX;
		public int mouseY;
		
		private void renderImage(Graphics2D g, MemFrame frame){
			currentGraphics=g;
			g.setColor(Color.LIGHT_GRAY);
			int step=8;
			for(int x=0;x<getWidth()+2;x+=step){
				for(int y=(x/step)%step;y<getHeight()+2;y+=step){
					g.drawRect(x+Rand.i(2), y+Rand.i(2), 1, 1);
				}
			}
			
			var bytes=frame.data();
			
			int width=Math.max(1, this.getWidth()/pixelsPerByte);
			
			BitSet drawn=new BitSet(bytes.length);
			
			
			DrawB drawByte=(i, color, withChar, force)->{
				if(i<bytes.length){
					if(!force&&drawn.get(i)) return;
					drawn.set(i);
				}
				
				if(i>=bytes.length) color=alpha(Color.RED, 0.4F);
				
				int b =i>=bytes.length?0xFF:bytes[i]&0xFF;
				int xi=i%width;
				int yi=i/width;
				
				
				g.drawImage(getByte(b, color, withChar), xi*pixelsPerByte, yi*pixelsPerByte, null);
			};
			
			Cluster[] cluster={null};
			
			List<Pointer> ptrs=new ArrayList<>();
			
			try{
				cluster[0]=Cluster.build(b->b.withMemoryView(bytes));
				
				annotateStruct(g, width, drawByte, cluster[0], cluster[0], 0, ptrs::add);
				for(Chunk chunk : cluster[0].getFirstChunk().physicalIterator()){
					fillChunk(drawByte, chunk, c->alpha(mix(c, Color.GRAY, 0.8F), c.getAlpha()/255F*0.8F));
				}
				for(Chunk chunk : cluster[0].getFirstChunk().physicalIterator()){
					annotateStruct(g, width, drawByte, cluster[0], chunk, chunk.getPtr().getValue(), ptrs::add);
				}
			}catch(Throwable e){
				new RuntimeException("failed to complete data annotation", e).printStackTrace();
			}
			
			
			initFont(g);
			
			var unusedColor=alpha(Color.GRAY, 0.5F);
			IntStream.range(0, bytes.length)
			         .parallel()
			         .forEach(i->drawByte.draw(i, unusedColor, true, false));
			
			
			g.setColor(Color.YELLOW);
			for(long id : frame.ids()){
				if(id>=bytes.length) continue;
				int i =(int)id;
				int xi=i%width;
				int yi=i/width;
				
				fillBit(8, xi*pixelsPerByte, yi*pixelsPerByte);
			}
			
			g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
			var siz  =Math.max(1, pixelsPerByte/8F);
			var sFul =new BasicStroke(siz);
			var sHalf=new BasicStroke(siz/2);
			
			g.setStroke(sFul);
			for(Pointer ptr : ptrs){
				
				int start=ptr.from;
				int end  =ptr.to;
				
				int pSiz=ptr.size;
				
				g.setColor(alpha(ptr.color, 0.5F));
				
				if(pSiz>1&&IntStream.range(start, start+pSiz).noneMatch(i->i%width==0)){
					g.setColor(alpha(ptr.color, 0.1F));
					g.setStroke(sHalf);
					drawLine(width, start, start+pSiz-1);
					g.setStroke(sFul);
				}
				
				g.setColor(alpha(ptr.color, 0.5F));
				drawArrow(width, start, end);
			}
		}
		
		private void calcSize(int bytesCount){
			
			int newPixelsPerByte=MathUtil.snap(pixelsPerByte, 3, getWidth()/2);
			
			while(true){
				int width         =getWidth()/newPixelsPerByte;
				int rows          =(int)Math.ceil(bytesCount/(double)width);
				int requiredHeight=rows*newPixelsPerByte;
				
				if(this.getHeight()<requiredHeight){
					newPixelsPerByte--;
				}else{
					break;
				}
			}
			
			setPixelsPerByte(newPixelsPerByte);
		}
		
		@Override
		public void paint(Graphics g){
			paint((Graphics2D)g);
		}
		
		public void paint(Graphics2D g){
			try{
				super.paint(g);
				
				if(frames.isEmpty()) return;
				var frame=frames.get(getPos());
				calcSize(frame.data().length);
				
				var image=render;
				
				if(shouldRerender){
					shouldRerender=false;
					
					if(image==null||image.getWidth()!=getWidth()||image.getHeight()!=getHeight()){
						image=getGraphicsConfiguration().createCompatibleImage(getWidth(), getHeight(), Transparency.TRANSLUCENT);
						render=image;
					}
					
					Graphics2D g1=image.createGraphics();
					
					g1.setComposite(AlphaComposite.Clear);
					g1.fillRect(0, 0, getWidth(), getHeight());
					g1.setComposite(AlphaComposite.SrcOver);
					
					try{
						renderImage(g1, frame);
					}catch(Throwable e){
						new RuntimeException("Failed to complete frame render", e).printStackTrace();
					}
					
					g1.dispose();
					
				}
				
				g.drawImage(image, 0, 0, null);
				
				int xByte=mouseX/pixelsPerByte;
				int yByte=mouseY/pixelsPerByte;
				
				int width=Math.max(1, this.getWidth()/pixelsPerByte);
				
				Rectangle area=new Rectangle(xByte*pixelsPerByte, yByte*pixelsPerByte, pixelsPerByte, pixelsPerByte);
				
				g.setColor(Color.CYAN.darker());
				initFont(g);
				drawStringIn(g, Integer.toString(yByte*width+xByte), area, true);
			}catch(Throwable e){
				e.printStackTrace();
			}
		}
	}
	
	
	private int pos;
	private int pixelsPerByte=300;
	
	private final List<MemFrame>               frames   =new ArrayList<>();
	private final Map<ByteInfo, BufferedImage> byteCache=new HashMap<>();
	private final JFrame                       frame    =new JFrame(){
	
	};
	
	public Display2D(){
		File f=new File("wind");
		
		try(var in=new BufferedReader(new FileReader(f))){
			frame.setLocation(Integer.parseInt(in.readLine()), Integer.parseInt(in.readLine()));
			frame.setSize(Integer.parseInt(in.readLine()), Integer.parseInt(in.readLine()));
		}catch(Throwable ignored){
			frame.setSize(800, 800);
			frame.setLocationRelativeTo(null);
		}
		
		var pan=new Pan();
		pan.setBackground(Color.GRAY);
		frame.setContentPane(pan);
		
		frame.setDefaultCloseOperation(frame.EXIT_ON_CLOSE);
		
		frame.addKeyListener(new KeyAdapter(){
			@Override
			public void keyPressed(KeyEvent e){
				if(e.getKeyChar()=='a'||e.getKeyCode()==37) setPos(getPos()-1);
				if(e.getKeyChar()=='d'||e.getKeyCode()==39) setPos(getPos()+1);
				setPos(MathUtil.snap(getPos(), 0, frames.size()-1));
				frame.repaint();
				
				if(e.getKeyCode()==122){
					frame.dispose();
					if(frame.isUndecorated()){
						frame.setUndecorated(false);
						frame.setExtendedState(JFrame.NORMAL);
						frame.setSize(800, 800);
						frame.setVisible(true);
						
					}else{
						frame.setUndecorated(true);
						frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
						frame.setVisible(true);
					}
				}
			}
		});
		
		frame.addComponentListener(new ComponentAdapter(){
			@Override
			public void componentResized(ComponentEvent e){
				setPixelsPerByte(300);
				
				frame.repaint();
			}
			
			@Override
			public void componentMoved(ComponentEvent e){
				super.componentMoved(e);
				if(!frame.isUndecorated()){
					try(var in=new BufferedWriter(new FileWriter(f))){
						in.write(Integer.toString(frame.getLocation().x));
						in.newLine();
						in.write(Integer.toString(frame.getLocation().y));
						in.newLine();
						in.write(Integer.toString(frame.getSize().width));
						in.newLine();
						in.write(Integer.toString(frame.getSize().height));
						in.newLine();
					}catch(Throwable ignored){ }
				}
			}
		});
		
		pan.addMouseMotionListener(new MouseMotionAdapter(){
			@Override
			public void mouseMoved(MouseEvent e){
				pan.mouseX=e.getX();
				pan.mouseY=e.getY();
				pan.repaint();
			}
			
			@Override
			public void mouseDragged(MouseEvent e){
				pan.mouseX=e.getX();
				pan.mouseY=e.getY();
				
				int width=pan.getWidth();
				int x    =MathUtil.snap(pan.mouseX, 0, width);
				
				float val=x/(float)width;
				
				setPos((int)(val*(frames.size()-1)));
				
				pan.repaint();
			}
		});
		
		pan.addMouseListener(new MouseAdapter(){
			@Override
			public void mouseClicked(MouseEvent e){
				if(frames.isEmpty()) return;
				frames.get(getPos()).printStackTrace();
				frame.repaint();
			}
		});
		
		frame.setVisible(true);
		frame.createBufferStrategy(2);
	}
	
	private void setPixelsPerByte(int pixelsPerByte){
		pixelsPerByte=Math.max(pixelsPerByte, 3);
		
		if(this.pixelsPerByte==pixelsPerByte) return;
		this.pixelsPerByte=pixelsPerByte;
		byteCache.clear();
		shouldRerender=true;
	}
	
	public void setPos(int pos){
		if(this.pos==pos) return;
		
		this.pos=pos;
		shouldRerender=true;
	}
	
	static record Pointer(int from, int to, int size, Color color){}
	
	private void annotateStruct(Graphics2D g, int width, DrawB drawByte,
	                            Cluster cluster,
	                            IOInstance instance, long instanceOffset,
	                            Consumer<Pointer> pointerRecord) throws IOException{
		annotateStruct(g, width, drawByte, cluster, new LinkedList<>(), instance, instanceOffset, pointerRecord);
	}
	
	private void annotateStruct(Graphics2D g, int width, DrawB drawByte,
	                            Cluster cluster, List<IOInstance> stack,
	                            IOInstance instance, long instanceOffset,
	                            Consumer<Pointer> pointerRecord) throws IOException{
		try{
			if(stack.contains(instance)) return;
			stack.add(instance);
			
			if(instance instanceof Chunk c){
				fillChunk(drawByte, c, Function.identity());
			}
			
			List<PairM<Long, IOInstance>> recurse=new ArrayList<>();
			
			IOStruct typ=instance.getStruct();
			
			var typeHash=instance.getStruct().instanceClass.getName().hashCode()&0xffffffffL;
			
			Random rand=new Random();
			instance.iterateOffsets((VariableNode<?> var, Offset off)->{
				try{
					rand.setSeed((((long)var.name.hashCode())<<32)|typeHash);
					
					var col=new Color(
						Color.HSBtoRGB(
							rand.nextFloat(),
							rand.nextFloat()/0.4F+0.6F,
							1F
						              )
					);
					
					g.setColor(alpha(col, 0.5F));
					
					Rectangle area;
					
					var varSize=(int)VariableNode.FixedSize.getSizeUnknown(instance, var);
					
					if(off instanceof Offset.BitOffset){
						final var from    =(int)(instanceOffset+off.getOffset());
						int       xPosFrom=from%width, yPosFrom=from/width;
						
						int fromB=Math.toIntExact(instanceOffset+off.getOffset());
						int toB  =fromB;
						
						var fl=(VariableNode.Flag<?>)var;
						
						int ib     =off.inByteBitOffset();
						var bitSize=Math.min(fl.getBitSize(), 8-ib);
						
						for(int i=0;i<bitSize;i++){
							toB++;
							if(toB%3==0) break;
						}
						
						
						int xi=ib%3;
						int yi=ib/3;
						
						area=new Rectangle(
							(int)(pixelsPerByte*(xPosFrom+xi/3D)), (int)(pixelsPerByte*(yPosFrom+yi/3D)),
							(int)(pixelsPerByte/3D*(toB-fromB)),
							pixelsPerByte/3*Math.max(1, bitSize/3));
						initFont(g, 1/3D);
						
					}else{
						
						int from=Math.toIntExact(instanceOffset+off.getOffset());
						int to  =from;
						
						for(int i=0;i<varSize;i++){
							to++;
							if(to%width==0) break;
						}
						
						int xPosFrom=from%width, yPosFrom=from/width;
						area=new Rectangle(pixelsPerByte*xPosFrom, pixelsPerByte*yPosFrom, pixelsPerByte*(to-from), pixelsPerByte);
						initFont(g);
						
						IntStream.range(from, from+varSize).forEach(i->drawByte.draw(i, mix(col, Color.GRAY, 0.65F), false, false));
						
					}
					
					Object valVal=var.getValueAsObj(instance);
					
					if(var instanceof VariableNode.SelfPointer<?>&&valVal instanceof IOInstance inst){
						var ptr   =((SelfPoint<?>)inst).getSelfPtr();
						var c     =ptr.getBlock(cluster);
						var valOff=ptr.globalOffset(cluster);
						
						pointerRecord.accept(new Pointer((int)(instanceOffset+off.getOffset()), (int)valOff, varSize, g.getColor()));
						
						recurse.add(new PairM<>(c.getPtr().getValue(), c));
						recurse.add(new PairM<>(valOff, inst));
					}else if(valVal instanceof IOInstance inst){
						try{
							long valOff=instanceOffset+off.getOffset();
							annotateStruct(g, width, drawByte, cluster, stack, inst, valOff, pointerRecord);
						}catch(IOException e){
							e.printStackTrace();
						}
					}else if(valVal instanceof ChunkPointer ptr){
						var color=g.getColor();
						try{
							recurse.add(new PairM<>(ptr.getValue(), ptr.dereference(cluster)));
						}catch(Throwable e){
							color=Color.RED;
						}
						
						pointerRecord.accept(new Pointer((int)(instanceOffset+off.getOffset()), ptr.getValueInt(), varSize, color));
					}else if(valVal instanceof ObjectPointer<?> ptr&&ptr.hasPtr()){
						
						if(ptr.getOffset()==0){
							annotateStruct(g, width, drawByte, cluster, stack, ptr.getBlock(cluster), ptr.getDataBlock().getValue(), pointerRecord);
						}
						
						var color=g.getColor();
						try{
							Object o=ptr.read(cluster);
							if(o instanceof IOInstance i){
								var oOff=ptr.globalOffset(cluster);
								recurse.add(new PairM<>(oOff, i));
							}
						}catch(Throwable e){
							new RuntimeException("failed to read object pointer "+ptr, e).printStackTrace();
							color=Color.RED;
						}
						
						pointerRecord.accept(new Pointer((int)(instanceOffset+off.getOffset()), (int)ptr.globalOffset(cluster), varSize, color));
					}
					
					if(area.width>0){
						try{
							String text=TextUtil.toString(valVal)
							                    .replace('\t', '↹')
							                    .replace('\n', '↵');
							
							drawStringIn(g, text, area, true);
						}catch(Throwable e){
							e.printStackTrace();
						}
						g.setColor(mul(g.getColor(), 0.4F));
						g.drawRect(area.x, area.y, area.width, area.height);
					}
					
				}catch(Throwable e){
					e.printStackTrace();
					try{
						int from   =Math.toIntExact(instanceOffset+off.getOffset());
						var varSize=(int)VariableNode.FixedSize.getSizeUnknown(instance, var);
						IntStream.range(from, from+varSize).forEach(i->drawByte.draw(i, Color.RED, false, true));
					}catch(Throwable e1){
						LogUtil.println(e1);
					}
				}
				
			});
			
			for(var i : recurse){
				annotateStruct(g, width, drawByte, cluster, stack, i.obj2, i.obj1, pointerRecord);
			}
		}finally{
			stack.remove(instance);
		}
	}
	
	boolean       shouldRerender=true;
	BufferedImage render        =null;
	
	synchronized BufferedImage getByte(int value, Color color, boolean withChar){
		return byteCache.computeIfAbsent(new ByteInfo(value, color, withChar), this::renderByte);
	}
	
	private Rectangle getStringBounds(Graphics2D g2, String str){
		FontRenderContext frc=g2.getFontRenderContext();
		GlyphVector       gv =g2.getFont().createGlyphVector(frc, str);
		return gv.getPixelBounds(null, 0, 0);
	}
	
	private void drawStringIn(Graphics2D g, String s, Rectangle area, boolean doStroke){
		var         rect=getStringBounds(g, s);
		FontMetrics fm  =g.getFontMetrics();
		
		double h=rect.getHeight();
		double w=rect.getWidth();
		
		var t=g.getTransform();
		
		g.translate(area.x, area.y);
		g.translate(Math.max(0, area.width-w)/2D, h+(area.height-h)/2);
		if(w>0){
			double scale=(area.width-1)/w;
			if(scale<1){
				g.scale(scale, 1);
			}
		}
		if(h>0){
			double scale=(area.height-1)/h;
			if(scale<1){
				g.scale(1, scale);
			}
		}
		if(doStroke){
			var aa=g.getRenderingHint(KEY_ANTIALIASING);
			var c =g.getColor();
			var st=g.getStroke();
			
			g.setColor(new Color(0, 0, 0, 0.5F));
			g.setStroke(new BasicStroke(1));
			g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
			
			g.draw(g.getFont().createGlyphVector(g.getFontRenderContext(), s).getOutline());
			
			g.setColor(c);
			g.setStroke(st);
			g.setRenderingHint(KEY_ANTIALIASING, aa);
		}
		g.drawString(s, 0, 0);
		g.setTransform(t);
	}
	
	private Font initFont(Graphics2D g){
		return initFont(g, 0.8);
	}
	
	private Font initFont(Graphics2D g, double sizeMul){
		var f=new Font(Font.MONOSPACED, Font.PLAIN, (int)(pixelsPerByte*sizeMul));
		g.setRenderingHint(KEY_TEXT_ANTIALIASING, f.getSize()>12?VALUE_TEXT_ANTIALIAS_ON:VALUE_TEXT_ANTIALIAS_OFF);
		
		g.setFont(f);
		return f;
	}
	
	private BufferedImage renderByte(ByteInfo info){
		var bb=new BufferedImage(pixelsPerByte, pixelsPerByte, BufferedImage.TYPE_INT_ARGB);
		
		Graphics2D g=bb.createGraphics();
//		g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
//		g.setRenderingHint(KEY_COLOR_RENDERING, VALUE_COLOR_RENDER_QUALITY);
//		g.setRenderingHint(KEY_ALPHA_INTERPOLATION, VALUE_ALPHA_INTERPOLATION_QUALITY);
		
		g.setColor(mul(info.color, 0.5F));
		g.fillRect(0, 0, pixelsPerByte, pixelsPerByte);
		
		g.setColor(info.color);
		
		for(FlagReader flags=new FlagReader(info.uVal, 8);flags.remainingCount()>0;){
			if(flags.readBoolBit()){
				fillBit(flags.readCount()-1, 0, 0);
			}
		}
		
		if(info.withChar){
			Font f=initFont(g);
			
			char c=(char)((byte)info.uVal);
			if(f.canDisplay(c)){
				String s=Character.toString(c);
				g.setColor(new Color(1, 1, 1, 0.6F));
				
				drawStringIn(g, s, new Rectangle(0, 0, pixelsPerByte, pixelsPerByte), false);
			}
		}
		
		g.dispose();
		return bb;
	}
	
	@Override
	protected void fillQuad(double x, double y, double width, double height){
		currentGraphics.fill(new Rectangle2D.Double(x, y, width, height));
	}
	
	@Override
	protected void outlineQuad(double x, double y, double width, double height){
		currentGraphics.draw(new Rectangle2D.Double(x, y, width, height));
	}
	
	@Override
	protected int getPixelsPerByte(){
		return pixelsPerByte;
	}
	
	@Override
	protected void drawLine(double xFrom, double yFrom, double xTo, double yTo){
		currentGraphics.draw(new Line2D.Double(
			                     xFrom*pixelsPerByte, yFrom*pixelsPerByte,
			                     xTo*pixelsPerByte, yTo*pixelsPerByte)
		                    );
	}
	
	@Override
	public void log(MemFrame frame){
		frames.add(frame);
		setPos(frames.size()-1);
		this.frame.repaint();
	}
	
	@Override
	public void finish(){ }
	
	@Override
	public void reset(){
		frames.clear();
		setPos(0);
		frame.repaint();
	}
	
	public int getPos(){
		return pos;
	}
}
