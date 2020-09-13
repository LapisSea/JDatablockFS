package test;

import com.lapissea.cfs.Cluster;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.util.MathUtil;
import com.lapissea.util.Rand;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.*;
import java.util.stream.IntStream;

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
			
			Cluster cluster=null;
			try{
				cluster=new Cluster(new MemoryData(bytes, true));
				
				for(Chunk chunk=cluster.getFirstChunk();chunk!=null;chunk=chunk.nextPhysical()){
					var chunkColor=chunk.isUsed()?Color.GREEN:Color.CYAN;
					var dataColor =mul(chunkColor, 0.5F);
					var freeColor =alpha(chunkColor, 0.4F);
					
					for(int i=(int)chunk.getPtr().value();i<chunk.dataStart();i++){
						
						drawByte.draw(i, chunkColor, false);
						
						if(!cluster.isLastPhysical(chunk)){
							drawByte.draw((int)chunk.dataEnd(), Color.RED, true);
						}
					}
					
					for(int i=0, j=(int)chunk.getCapacity();i<j;i++){
						drawByte.draw((int)(i+chunk.dataStart()), i>=chunk.getSize()?freeColor:dataColor, true);
					}
				}
			}catch(Throwable e){
//				LogUtil.println(e);
				e.printStackTrace();
			}
			
			for(int i=0;i<bytes.length;i++){
				if(drawn.get(i)) continue;
				
				drawByte.draw(i, Color.GRAY, true);
			}
			
			g.setColor(Color.YELLOW);
			for(long id : frame.ids()){
				if(id>=bytes.length) continue;
				int i =(int)id;
				int b =bytes[i]&0xFF;
				int xi=i%width;
				int yi=i/width;
				
				int off=(int)Math.round(pixelsPerByte/3D*2);
				int siz=pixelsPerByte-off;
				g.fillRect(xi*pixelsPerByte+off, yi*pixelsPerByte+off, siz, siz);
			}
			
			if(cluster!=null){
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				var siz  =Math.max(1, pixelsPerByte/8F);
				var sFul =new BasicStroke(siz);
				var sHalf=new BasicStroke(siz/2);
				
				g.setStroke(sFul);
				try{
					for(Chunk chunk=cluster.getFirstChunk();chunk!=null;chunk=chunk.nextPhysical()){
						if(!chunk.hasNext()) continue;
						var chunkColor=chunk.isUsed()?Color.GREEN:Color.CYAN;
						
						try{
							chunk.getNextPtr().dereference(cluster);
						}catch(Throwable e){
							chunkColor=Color.RED;
						}
						
						chunkColor=alpha(mix(chunkColor, Color.LIGHT_GRAY, 0.5F), 0.5F);
						g.setColor(chunkColor);
						
						VariableNode<?> ptrNode=chunk.structType().varByName("nextPtr");
						
						int start=(int)(chunk.getPtr().value()+chunk.calcVarOffset(ptrNode).getOffset());
						int pSiz =(int)ptrNode.mapSize(chunk);
						
						if(IntStream.range(start, start+pSiz).noneMatch(i->i%width==0)){
							g.setStroke(sHalf);
							drawLine(g, width, start, start+pSiz-1);
							g.setStroke(sFul);
						}
						
						drawArrow(g, width, start, (int)chunk.getNextPtr().value());
					}
				}catch(Throwable e){
					e.printStackTrace();
				}
			}
		}
		
		private void calcSize(int bytesCount){
			
			int newPixelsPerByte=Math.min(pixelsPerByte, getWidth()/2);
			
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
		createBufferStrategy(2);
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
	
	BufferedImage render;
	
	BufferedImage getByte(int value, Color color, boolean withChar){
		return byteCache.computeIfAbsent(new ByteInfo(value, color, withChar), this::renderByte);
	}
	
	private BufferedImage renderByte(ByteInfo info){
		var bb=new BufferedImage(pixelsPerByte, pixelsPerByte, BufferedImage.TYPE_INT_ARGB);
		var g =bb.createGraphics();
//		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
		
		g.setColor(mul(info.color, 0.5F));
		g.fillRect(0, 0, pixelsPerByte, pixelsPerByte);
		
		{
			g.setColor(info.color);
			var f=new FlagReader(info.uVal, 8);
			
			
			for(int i=0;i<8;i++){
				if(f.readBoolBit()){
					int   xi =i%3;
					int   yi =i/3;
					float pxS=pixelsPerByte/3F;
					
					g.fill(new Rectangle2D.Float(xi*pxS, yi*pxS, pxS, pxS));
				}
			}
		}
		
		if(info.withChar){
			var f=new Font(Font.MONOSPACED, Font.PLAIN, (int)(pixelsPerByte*0.8F));
			g.setFont(f);
			char c=(char)((byte)info.uVal);
			if(f.canDisplay(c)){
				String s=Character.toString(c);
				g.setColor(new Color(1, 1, 1, 0.6F));
				
				g.drawString(s, (pixelsPerByte-g.getFontMetrics().stringWidth(s))/2, (int)(pixelsPerByte*0.9F));
				
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
