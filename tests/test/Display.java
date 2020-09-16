package test;

import com.lapissea.cfs.Cluster;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.Offset;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.util.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.*;
import java.util.stream.IntStream;

import static java.awt.RenderingHints.*;

public class Display extends JFrame{
	
	static{
		System.setProperty("sun.java2d.d3d", "true");
//		Toolkit.getDefaultToolkit().setDynamicLayout(false);
	}
	
	private record ByteInfo(int uVal, Color color, boolean withChar){}
	
	private class Pan extends JPanel{
		
		private void drawArrow(Graphics2D g, int width, int from, int to){
			int xPosFrom=from%width, yPosFrom=from/width;
			int xPosTo  =to%width, yPosTo=to/width;
			
			double xFrom=xPosFrom+0.5, yFrom=yPosFrom+0.5;
			double xTo  =xPosTo+0.5, yTo=yPosTo+0.5;
			
			double xMid=(xFrom+xTo)/2, yMid=(yFrom+yTo)/2;
			
			double angle=Math.atan2(xTo-xFrom, yTo-yFrom);
			
			double arrowSize=0.4;
			
			double sin=Math.sin(angle)*arrowSize/2;
			double cos=Math.cos(angle)*arrowSize/2;
			
			drawLine(g, xMid+sin, yMid+cos, xMid-sin-cos, yMid-cos+sin);
			drawLine(g, xMid+sin, yMid+cos, xMid-sin+cos, yMid-cos-sin);
			
			drawLine(g, xFrom, yFrom, xTo, yTo);
		}
		
		private void drawLine(Graphics2D g, int width, int from, int to){
			int xPosFrom=from%width, yPosFrom=from/width;
			int xPosTo  =to%width, yPosTo=to/width;
			
			drawLine(g, xPosFrom+0.5, yPosFrom+0.5, xPosTo+0.5, yPosTo+0.5);
		}
		private void drawLine(Graphics2D g, double xFrom, double yFrom, double xTo, double yTo){
			g.draw(new Line2D.Double(
				xFrom*pixelsPerByte, yFrom*pixelsPerByte,
				xTo*pixelsPerByte, yTo*pixelsPerByte)
			);
		}
		
		private void renderImage(Graphics2D g, MemFrame frame){
			
			var bytes=frame.data();
			
			int width=Math.max(1, this.getWidth()/pixelsPerByte);
			
			BitSet drawn=new BitSet(bytes.length);
			
			
			interface DrawB{
				void draw(int index, Color color, boolean withChar);
			}
			
			DrawB drawByte=(i, color, withChar)->{
				if(i>=bytes.length) color=alpha(Color.RED, 0.4F);
				
				int b =i>=bytes.length?0xFF:bytes[i]&0xFF;
				int xi=i%width;
				int yi=i/width;
				
				if(i<bytes.length) drawn.set(i);
				
				g.drawImage(getByte(b, color, withChar), xi*pixelsPerByte, yi*pixelsPerByte, null);
			};
			
			Cluster[] cluster={null};
			
			Iterable<Chunk> physicalIterator=()->{
				Chunk c1;
				try{
					c1=cluster[0]==null?null:cluster[0].getFirstChunk();
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
				
				return new Iterator<>(){
					Chunk ch=c1;
					
					@Override
					public boolean hasNext(){
						return ch!=null;
					}
					@Override
					public Chunk next(){
						Chunk c=ch;
						try{
							ch=c.nextPhysical();
						}catch(IOException e){
							LogUtil.println(e);
							ch=null;
						}
						return c;
					}
				};
			};
			
			try{
				cluster[0]=new Cluster(new MemoryData(bytes, true));
				for(Chunk chunk : physicalIterator){
					var chunkColor=chunk.isUsed()?Color.GREEN:Color.CYAN;
					var dataColor =mul(chunkColor, 0.5F);
					var freeColor =alpha(chunkColor, 0.4F);
					
					for(int i=(int)chunk.getPtr().value();i<chunk.dataStart();i++){
						
						drawByte.draw(i, chunkColor, false);
						
						if(!cluster[0].isLastPhysical(chunk)){
							drawByte.draw((int)chunk.dataEnd(), Color.RED, true);
						}
					}
					
					for(int i=0, j=(int)chunk.getCapacity();i<j;i++){
						drawByte.draw((int)(i+chunk.dataStart()), i>=chunk.getSize()?freeColor:dataColor, true);
					}
					
				}
			}catch(Throwable e){
				LogUtil.println(e);
//				e.printStackTrace();
			}
			
			for(int i=0;i<bytes.length;i++){
				if(drawn.get(i)) continue;
				
				drawByte.draw(i, Color.GRAY, true);
			}
			
			g.setColor(Color.YELLOW);
			for(long id : frame.ids()){
				if(id>=bytes.length) continue;
				int i =(int)id;
				int xi=i%width;
				int yi=i/width;
				
				fillBit(g, 8, xi*pixelsPerByte, yi*pixelsPerByte);
			}
			
			
			for(Chunk chunk : physicalIterator){
				annotateStruct(g, width, chunk);
			}
			
			annotateStruct(g, width, cluster[0]);
			
			initFont(g);
			
			g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
			var siz  =Math.max(1, pixelsPerByte/8F);
			var sFul =new BasicStroke(siz);
			var sHalf=new BasicStroke(siz/2);
			
			g.setStroke(sFul);
			for(Chunk chunk : physicalIterator){
				if(!chunk.hasNext()) continue;
				var chunkColor=chunk.isUsed()?Color.GREEN:Color.CYAN;
				
				try{
					chunk.getNextPtr().dereference(cluster[0]);
				}catch(Throwable e){
					chunkColor=Color.RED;
				}
				
				chunkColor=alpha(mix(chunkColor, Color.LIGHT_GRAY, 0.5F), 0.5F);
				g.setColor(chunkColor);
				
				VariableNode<ChunkPointer> ptrNode=chunk.structType().getVar("nextPtr");
				
				int start=(int)(chunk.getPtr().value()+chunk.calcVarOffset(ptrNode).getOffset());
				int pSiz =(int)ptrNode.mapSize(chunk);
				
				if(IntStream.range(start, start+pSiz).noneMatch(i->i%width==0)){
					g.setStroke(sHalf);
					drawLine(g, width, start, start+pSiz-1);
					g.setStroke(sFul);
				}
				
				drawArrow(g, width, start, (int)chunk.getNextPtr().value());
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
			try{
				super.paint(g);
				
				g.setColor(Color.LIGHT_GRAY);
				int step=8;
				for(int x=0;x<getWidth()+2;x+=step){
					for(int y=(x/step)%step;y<getHeight()+2;y+=step){
						g.drawRect(x+Rand.i(2), y+Rand.i(2), 1, 1);
					}
				}
				
				if(frames.isEmpty()) return;
				var frame=frames.get(getPos());
				calcSize(frame.data().length);
				
				var image=render;
				
				if(image==null||image.getWidth()!=getWidth()||image.getHeight()!=getHeight()){
					image=new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
					Graphics2D g1=image.createGraphics();
					renderImage(g1, frame);
					g1.dispose();
					render=image;
				}
				
				g.drawImage(image, 0, 0, null);
				
			}catch(Throwable e){
				e.printStackTrace();
			}
		}
	}
	
	private static Color mul(Color color, float mul){
		return new Color(Math.round(color.getRed()*mul), Math.round(color.getGreen()*mul), Math.round(color.getBlue()*mul), color.getAlpha());
	}
	private static Color add(Color color, Color other){
		return new Color(
			Math.min(255, color.getRed()+other.getRed()),
			Math.min(255, color.getGreen()+other.getGreen()),
			Math.min(255, color.getBlue()+other.getBlue()),
			Math.min(255, color.getAlpha()+other.getAlpha())
		);
	}
	private static Color alpha(Color color, float alpha){
		return new Color(
			color.getRed(),
			color.getGreen(),
			color.getBlue(),
			(int)(alpha*255)
		);
	}
	private static Color mix(Color color, Color other, float mul){
		return add(mul(color, 1-mul), mul(other, mul));
	}
	
	
	private int pos;
	private int pixelsPerByte=300;
	
	private final List<MemFrame>               frames   =new ArrayList<>();
	private final Map<ByteInfo, BufferedImage> byteCache=new HashMap<>();
	
	public Display(){
		setSize(800, 800);
		
		var pan=new Pan();
		pan.setBackground(Color.GRAY);
		add(pan);
		
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		
		addKeyListener(new KeyAdapter(){
			@Override
			public void keyTyped(KeyEvent e){
				if(e.getKeyChar()=='a') setPos(getPos()-1);
				if(e.getKeyChar()=='d') setPos(getPos()+1);
				setPos(MathUtil.snap(getPos(), 0, frames.size()-1));
				
				repaint();
			}
		});
		
		addComponentListener(new ComponentAdapter(){
			@Override
			public void componentResized(ComponentEvent e){
				setPixelsPerByte(300);
				
				repaint();
			}
		});
		
		addMouseMotionListener(new MouseMotionAdapter(){
			@Override
			public void mouseDragged(MouseEvent e){
				int width=pan.getWidth();
				int x    =MathUtil.snap(e.getXOnScreen()-pan.getLocationOnScreen().x, 0, width);
				
				float val=x/(float)width;
				
				setPos((int)(val*(frames.size()-1)));
				
				repaint();
			}
			@Override
			public void mouseMoved(MouseEvent e){
				repaint();
			}
		});
		
		addMouseListener(new MouseAdapter(){
			@Override
			public void mouseClicked(MouseEvent e){
				frames.get(getPos()).e().printStackTrace();
				repaint();
			}
		});
		
		setBackground(Color.GRAY);
		setVisible(true);
		createBufferStrategy(3);
	}
	
	private void setPixelsPerByte(int pixelsPerByte){
		pixelsPerByte=Math.max(pixelsPerByte, 3);
		
		if(this.pixelsPerByte==pixelsPerByte) return;
		this.pixelsPerByte=pixelsPerByte;
		byteCache.clear();
	}
	
	public void setPos(int pos){
		if(this.pos==pos) return;
		
		this.pos=pos;
		render=null;
	}
	
	private void annotateStruct(Graphics2D g, int width, IOStruct.Instance instance){
		IOStruct typ=instance.structType();
		
		Random rand=new Random();
		instance.iterateOffsets((VariableNode<?> var, Offset off)->{
			rand.setSeed(var.name.hashCode());
			
			g.setColor(
				alpha(new Color(
					      Color.HSBtoRGB(
						      rand.nextFloat(),
						      rand.nextFloat()/0.4F+0.6F,
						      1F
					      )),
				      0.5F));
			
			Rectangle area;
			
			if(off instanceof Offset.BitOffset){
				final var from    =(int)(instance.getStructOffset()+off.getOffset());
				int       xPosFrom=from%width, yPosFrom=from/width;
				
				int fromB=Math.toIntExact(instance.getStructOffset()+off.getOffset());
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
				
				int from=Math.toIntExact(instance.getStructOffset()+off.getOffset());
				int to  =from;
				
				for(int i=0;i<VariableNode.FixedSize.getSizeUnknown(instance, var);i++){
					to++;
					if(to%width==0) break;
				}
				
				int xPosFrom=from%width, yPosFrom=from/width;
				area=new Rectangle(pixelsPerByte*xPosFrom, pixelsPerByte*yPosFrom, pixelsPerByte*(to-from), pixelsPerByte);
				initFont(g);
			}
			
			drawStringIn(g, TextUtil.toString(var.toString(instance).getValue()), area, true);
			g.setColor(mul(g.getColor(), 0.4F));
			g.drawRect(area.x, area.y, area.width, area.height);
		});
		
	}
	
	BufferedImage render;
	
	BufferedImage getByte(int value, Color color, boolean withChar){
		return byteCache.computeIfAbsent(new ByteInfo(value, color, withChar), this::renderByte);
	}
	
	private void fillBit(Graphics2D g, int index, float xOff, float yOff){
		int   xi =index%3;
		int   yi =index/3;
		float pxS=pixelsPerByte/3F;
		
		float x1=xi*pxS;
		float y1=yi*pxS;
		float x2=(xi+1)*pxS;
		float y2=(yi+1)*pxS;
		
		g.fill(new Rectangle2D.Float(xOff+x1, yOff+y1, x2-x1, y2-y1));
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
			double scale=area.width/w;
			if(scale<1){
				g.scale(scale, 1);
			}
		}
		if(h>0){
			double scale=area.height/h;
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
				fillBit(g, flags.readCount()-1, 0, 0);
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
	
	
	public void push(MemFrame frame){
		frames.add(frame);
		setPos(frames.size()-1);
		repaint();
	}
	public int getPos(){
		return pos;
	}
}
