package com.lapissea.fsf;

import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.util.LogUtil;
import com.lapissea.util.MathUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.WeakValueHashMap;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static com.lapissea.util.PoolOwnThread.*;
import static java.awt.RenderingHints.*;
import static javax.swing.SwingUtilities.*;

public interface Renderer{
	
	Executor runner=Executors.newWorkStealingPool();
	
	private static CompletableFuture<BufferedImage> renderFile(Snapshot snap, int minWidth, int pixelScale, boolean printError){
		return async(()->{
			try{
				return snap.copy.renderFile(minWidth, snap.ids, pixelScale, printError);
			}catch(IOException e){
				throw UtilL.uncheckedThrow(e);
			}
		}, runner);
	}
	
	private static CompletableFuture<BufferedImage> renderFile(Snapshot snap, int minWidth, int minHeight, int pixelScale, boolean printError){
		return async(()->{
			try{
				return snap.copy.renderFile(minWidth, minHeight, snap.ids, pixelScale, printError);
			}catch(IOException e){
				throw UtilL.uncheckedThrow(e);
			}
		}, runner);
	}
	
	private static BufferedImage noAlpha(BufferedImage img){
		BufferedImage copy=new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D    g2d =copy.createGraphics();
		g2d.setColor(Color.DARK_GRAY);
		g2d.fillRect(0, 0, copy.getWidth(), copy.getHeight());
		g2d.drawImage(img, 0, 0, null);
		g2d.dispose();
		return copy;
	}
	
	class Snapshot{
		FileSystemInFile copy;
		long[]           ids;
		Throwable        stackTrace;
		
		public Snapshot(FileSystemInFile copy, long[] ids, Throwable stackTrace){
			this.copy=copy;
			this.ids=ids;
			this.stackTrace=stackTrace;
		}
	}

//	class MP4 extends Png{
//
//		SequenceEncoder encoderArr;
//
//		public MP4(int size, int pixelScale){
//			super(size, pixelScale);
//		}
//
//		@Override
//		public boolean doAll(){
//			return true;
//		}
//
//		@Override
//		protected void save(BufferedImage img) throws IOException{
//			if(task!=null){
//				task.join();
//				task=null;
//			}
//			getEncoder().encodeNativeFrame(AWTUtil.fromBufferedImageRGB(Renderer.noAlpha(img)));
//		}
//
//		@Override
//		public void finish(){
//			try{
//				getEncoder().finish();
//			}catch(IOException e){
//				e.printStackTrace();
//			}
//		}
//
//		private SequenceEncoder getEncoder(){
//			if(encoderArr==null){
//				try{
//					encoderArr=new SequenceEncoder(NIOUtils.writableChannel(new File("output.mp4")), Rational.R(60, 1), Format.MOV, Codec.H264, null);
//				}catch(IOException e){
//					throw UtilL.uncheckedThrow(e);
//				}
//			}
//			return encoderArr;
//		}
//
//	}
	
	class Png implements Renderer{
		CompletableFuture<Void> task;
		
		private final int minSize;
		private       int pixelScale;
		protected     int count;
		
		public Png(int minSize, int pixelScale){
			this.minSize=minSize;
			this.pixelScale=pixelScale;
		}
		
		@Override
		public boolean doAll(){
			return false;
		}
		
		@Override
		public void snapshot(Snapshot snap){
			finish();
			task=Renderer.renderFile(snap, minSize, minSize, pixelScale, false).thenAccept(img->{
				try{
					save(img);
				}catch(IOException e){
					e.printStackTrace();
				}
			});
		}
		
		protected void save(BufferedImage img) throws IOException{
			ImageIO.write(img, "png", new File((++count)+".png"));
		}
		
		@Override
		public void finish(){
			if(task!=null){
				task.join();
				task=null;
			}
		}
	}
	
	class Jpg extends Png{
		
		public Jpg(int minSize, int pixelScale){
			super(minSize, pixelScale);
		}
		
		@Override
		protected void save(BufferedImage img) throws IOException{
			ImageIO.write(Renderer.noAlpha(img), "jpg", new File((++count)+".jpg"));
		}
	}
	
	class None implements Renderer{
		
		@Override
		public boolean doAll(){
			return false;
		}
		
		@Override
		public void snapshot(Snapshot snap){ }
		
		@Override
		public void finish(){ }
	}
	
	class GUI implements Renderer{
		
		private List<Snapshot> snapshots =new ArrayList<>();
		private int            pixelScale=1;
		
		private JFrame  jframe;
		private float[] imgPos={0};
		private int[]   pos   ={0, 0};
		
		Map<Integer, BufferedImage> cache=new WeakValueHashMap<Integer, BufferedImage>().defineStayAlivePolicy(5);
		
		public GUI(){
			jframe=new JFrame(){
				@Override
				public void paint(Graphics g){
					if(snapshots.isEmpty()) invokeLater(this::repaint);
					else setGUIImg(getImg(safePos()));
					super.paint(g);
				}
			};
			
			var p=new JPanel();
			p.setBackground(Color.DARK_GRAY);
			p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
			
			jframe.setVisible(true);
			
			jframe.setSize(1000, 750);
			jframe.setLocationRelativeTo(null);
			jframe.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
			jframe.addComponentListener(new ComponentAdapter(){
				int lastW;
				int lastH;
				
				@Override
				public void componentResized(ComponentEvent e){
					if(snapshots.isEmpty()) return;
					invokeLater(()->{
						boolean noChange=recalcPixelSize();
						
						if(noChange&&lastW==getWidthPx()&&lastH==getHeightPx()) return;
						
						cache.clear();
						jframe.revalidate();
						jframe.repaint();
						lastW=getWidthPx();
						lastH=getHeightPx();
					});
				}
			});
			
			var adp=new MouseAdapter(){
				@Override
				public void mouseMoved(MouseEvent e){
					
					Point rootPaneOrigin=jframe.getRootPane().getContentPane().getLocationOnScreen();
					Point myComp2Origin =jframe.getLocationOnScreen();
					
					pos[0]=e.getX()+(int)(myComp2Origin.getX()-rootPaneOrigin.getX());
					pos[1]=e.getY()+(int)(myComp2Origin.getY()-rootPaneOrigin.getY());
					
					invokeLater(jframe::repaint);
				}
				
				@Override
				public void mouseDragged(MouseEvent e){
					imgPos[0]=MathUtil.snap(snapshots.size()*(e.getX()/(float)jframe.getWidth()), 0, snapshots.size()-1);
					setGUIImg(getImg(safePos()));
					mouseMoved(e);
				}
				
				@Override
				public void mouseWheelMoved(MouseWheelEvent e){
					imgPos[0]=(float)MathUtil.snap(e.getPreciseWheelRotation()+imgPos[0], 0, snapshots.size());
					setGUIImg(getImg(safePos()));
				}
				
				@Override
				public void mouseClicked(MouseEvent e){
					var snap=snapshots.get(safePos());
					LogUtil.printlnEr("=============");
					Renderer.renderFile(snap, getWidthPx(), pixelScale, true).join();
					LogUtil.printlnEr("=============");
					snap.stackTrace.printStackTrace();
					LogUtil.printlnEr("=============");
				}
			};
			
			jframe.addMouseMotionListener(adp);
			jframe.addMouseWheelListener(adp);
			jframe.addMouseListener(adp);
			
			jframe.setContentPane(p);
			jframe.requestFocus();
		}
		
		
		private int safePos(){
			return MathUtil.snap((int)imgPos[0], 0, snapshots.size()-1);
		}
		
		private int getWidthPx(){
			return Math.max(5, getJFrame().getContentPane().getWidth()/(pixelScale*3));
		}
		
		private int getHeightPx(){
			return getJFrame().getContentPane().getHeight()/(pixelScale*3);
		}
		
		public JFrame getJFrame(){
			return jframe;
		}
		
		private boolean recalcPixelSize(){
			var h=getJFrame().getContentPane().getHeight();
			
			long siz;
			try{
				siz=snapshots.get(safePos()).copy.header.source.getSize();
			}catch(IOException ex){
				throw UtilL.uncheckedThrow(ex);
			}
			var oldSize=pixelScale;
			
			pixelScale=2;
			
			while(true){
				int lineCount=(int)Math.ceil(((double)siz)/getWidthPx());
				if(h<lineCount*pixelScale*3) break;
				pixelScale++;
			}
			
			pixelScale--;
			
			if(pixelScale!=oldSize) cache.clear();
			
			return pixelScale==oldSize;
		}
		
		BufferedImage render(int i){
			return Renderer.renderFile(snapshots.get(i), getWidthPx(), pixelScale, false).join();
		}
		
		@SuppressWarnings("AutoBoxing")
		BufferedImage getImg(int index){
			return cache.computeIfAbsent(index, this::render);
		}
		
		void setGUIImg(BufferedImage img){
			invokeLater(()->{
				var jf    =getJFrame();
				var target=jf.getContentPane();
				try{
					var l =(JLabel)target.getComponent(0);
					var ic=(ImageIcon)l.getIcon();
					var i =ic.getImage();
					if(i==img) return;
				}catch(Exception ignored){}
				
				var thisPos=safePos();
				var lab=new JLabel(new ImageIcon(img)){
					Chunk hoverChunk;
					Color hoverCol=Color.WHITE;
					
					int pxW=getWidthPx();
					
					void outlineChunk(Graphics2D g, Chunk chunk){
						var   pp       =pixelScale*3;
						float lineWidth=Math.max(1F/pp, 1/25F)*pp;
						g.setStroke(new BasicStroke(lineWidth));
						
						var start=chunk.getOffset();
						for(long i=start, end=chunk.nextPhysicalOffset();i<end;i++){
							int x=(int)(i%pxW);
							int y=(int)(i/pxW);
							
							if(i+pxW >= end){
								int drawX=x*pp;
								int drawY=y*pp+pp-1;
								
								g.drawLine(drawX, drawY, drawX+pp, drawY);
							}
							
							if(i-pxW<start){
								int drawX=x*pp;
								int drawY=y*pp;
								
								g.drawLine(drawX, drawY, drawX+pp, drawY);
							}
							
							if(i==start||x==0){
								int drawX=x*pp;
								int drawY=y*pp;
								
								g.drawLine(drawX, drawY, drawX, drawY+pp);
							}
							if(i+1==end||x+1==pxW){
								int drawX=x*pp+pp-1;
								int drawY=y*pp;
								
								g.drawLine(drawX, drawY, drawX, drawY+pp);
							}
						}
					}
					
					@Override
					public void paint(Graphics g){
						paint((Graphics2D)g);
					}
					
					public void paint(Graphics2D g){
						super.paint(g);
						
						var pp=pixelScale*3;
						
						var width  =img.getWidth();
						var hoverX =pos[0];
						var hoverY =pos[1];
						var bytePos=(hoverX/pp+hoverY/pp*width/pp);
						
						findChunk:
						try{
							if(hoverChunk!=null&&hoverChunk.overlaps(bytePos, bytePos+1)){
								break findChunk;
							}
							
							hoverChunk=null;
							hoverCol=Color.WHITE;
							var header=snapshots.get(thisPos).copy.header;
							var chunks=header.allChunks(true);
							for(var e : chunks.entrySet()){
								var chains=e.getValue();
								for(var chain : chains){
									for(ChunkLink link : chain){
										if(!link.sourceValidChunk) continue;
										Chunk chunk=header.getByOffset(link.sourcePos);
										if(chunk.overlaps(bytePos, bytePos+1)){
											hoverChunk=chunk;
											hoverCol=e.getKey().displayColor();
											hoverCol=new Color(Math.min(255, hoverCol.getRed()+100), Math.min(255, hoverCol.getGreen()+100), Math.min(255, hoverCol.getBlue()+100));
//											break findChunk; //can't use bc of the hacky header file module
										}
									}
								}
							}
						}catch(Throwable ignored){ }
						
						
						g.setColor(hoverCol);
						g.setFont(new Font(Font.MONOSPACED, Font.BOLD, pixelScale*2));
						
						g.setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_ON);
						g.drawString(bytePos+"", pos[0]/pp*pp, pos[1]/pp*pp+pp/2);
						
						if(hoverChunk!=null){
							try{
								g.setColor(hoverCol.darker().darker());
								hoverChunk.walkOverWholeChain(c->outlineChunk(g, c));
								
								g.setColor(hoverCol);
								outlineChunk(g, hoverChunk);
							}catch(IOException e){
								e.printStackTrace();
							}
						}
					}
				};
				
				target.removeAll();
				target.add(lab);
				
				if(jf.getContentPane().getHeight()<img.getHeight()){
					recalcPixelSize();
					
					cache.clear();
				}
				
				jf.revalidate();
				jf.repaint();
			});
		}
		
		@Override
		public boolean doAll(){
			return true;
		}
		
		@Override
		public void snapshot(Snapshot snap){
			int i=snapshots.size();
			snapshots.add(snap);
			imgPos[0]=i;
			
			recalcPixelSize();
			invokeLater(getJFrame()::repaint);
		}
		
		@Override
		public void finish(){}
	}
	
	boolean doAll();
	
	void snapshot(Snapshot snap) throws IOException;
	
	void finish();
	
}
