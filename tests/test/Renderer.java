package test;

import com.lapissea.fsf.FileSystemInFile;
import com.lapissea.util.LogUtil;
import com.lapissea.util.MathUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.WeakValueHashMap;
import org.jcodec.api.SequenceEncoder;
import org.jcodec.common.Codec;
import org.jcodec.common.Format;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Rational;
import org.jcodec.scale.AWTUtil;

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

import static com.lapissea.util.PoolOwnThread.*;
import static java.awt.RenderingHints.*;
import static javax.swing.SwingUtilities.*;

public interface Renderer{
	
	Executor runner=e->{
		var t=new Thread(e);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	};
	
	private static CompletableFuture<BufferedImage> renderFile(Snapshot snap, int minWidth, int pixelScale){
		return async(()->{
			try{
				return snap.copy.renderFile(minWidth, snap.ids, pixelScale);
			}catch(IOException e){
				throw UtilL.uncheckedThrow(e);
			}
		}, runner);
	}
	
	private static CompletableFuture<BufferedImage> renderFile(Snapshot snap, int minWidth, int minHeight, int pixelScale){
		return async(()->{
			try{
				return snap.copy.renderFile(minWidth, minHeight, snap.ids, pixelScale);
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
	
	class MP4 extends Png{
		
		SequenceEncoder encoderArr;
		
		public MP4(int size, int pixelScale){
			super(size, pixelScale);
		}
		
		@Override
		public boolean doAll(){
			return true;
		}
		
		@Override
		protected void save(BufferedImage img) throws IOException{
			if(task!=null){
				task.join();
				task=null;
			}
			getEncoder().encodeNativeFrame(AWTUtil.fromBufferedImageRGB(Renderer.noAlpha(img)));
		}
		
		@Override
		public void finish(){
			try{
				getEncoder().finish();
			}catch(IOException e){
				e.printStackTrace();
			}
		}
		
		private SequenceEncoder getEncoder(){
			if(encoderArr==null){
				try{
					encoderArr=new SequenceEncoder(NIOUtils.writableChannel(new File("output.mp4")), Rational.R(60, 1), Format.MOV, Codec.H264, null);
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
			return encoderArr;
		}
		
	}
	
	class Png implements Renderer{
		CompletableFuture<Void> task;
		
		private final int minSize;
		private final int pixelScale;
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
			task=Renderer.renderFile(snap, minSize, minSize, pixelScale).thenAccept(img->{
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
		
		private       List<Snapshot> snapshots=new ArrayList<>();
		private final int            pixelScale;
		JFrame  jframe=null;
		float[] imgPos={0};
		int[]   pos   ={0, 0};
		
		Map<Integer, BufferedImage> cache=new WeakValueHashMap<Integer, BufferedImage>().defineStayAlivePolicy(5);
		
		public GUI(int pixelScale){
			this.pixelScale=pixelScale;
		}
		
		private int safePos(){
			return MathUtil.snap((int)imgPos[0], 0, snapshots.size()-1);
		}
		
		private int getWidth(){
			return Math.max(5, getJFrame().getContentPane().getWidth()/(pixelScale*3));
		}
		
		public synchronized JFrame getJFrame(){
			if(jframe==null){
				
				var jf=new JFrame(){
					@Override
					public void paint(Graphics g){
						setGUIImg(getImg(safePos()));
						super.paint(g);
					}
				};
				
				var p=new JPanel();
				p.setBackground(Color.DARK_GRAY);
				p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
				
				jf.setVisible(true);
				
				jf.setSize(1000, 700);
				jf.setLocationRelativeTo(null);
				jf.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
				jf.addComponentListener(new ComponentAdapter(){
					@Override
					public void componentResized(ComponentEvent e){
						invokeLater(()->{
							if(getWidth()*pixelScale*3==getImg(safePos()).getWidth()) return;
							cache.clear();
							jf.revalidate();
						});
					}
				});
				
				var adp=new MouseAdapter(){
					@Override
					public void mouseMoved(MouseEvent e){
						
						Point rootPaneOrigin=jf.getRootPane().getContentPane().getLocationOnScreen();
						Point myComp2Origin =jf.getLocationOnScreen();
						
						pos[0]=e.getX()+(int)(myComp2Origin.getX()-rootPaneOrigin.getX());
						pos[1]=e.getY()+(int)(myComp2Origin.getY()-rootPaneOrigin.getY());
						
						invokeLater(jf::repaint);
					}
					
					@Override
					public void mouseDragged(MouseEvent e){
						imgPos[0]=MathUtil.snap(snapshots.size()*(e.getX()/(float)jf.getWidth()), 0, snapshots.size()-1);
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
						snapshots.get(MathUtil.snap((int)imgPos[0], 0, snapshots.size()-1)).stackTrace.printStackTrace();
					}
				};
				
				jf.addMouseMotionListener(adp);
				jf.addMouseWheelListener(adp);
				jf.addMouseListener(adp);
				
				jf.setContentPane(p);
				
				jframe=jf;
			}
			
			return jframe;
		}
		
		BufferedImage render(int i){
			return Renderer.renderFile(snapshots.get(i), getWidth(), pixelScale).join();
		}
		
		BufferedImage getImg(int index){
			return cache.computeIfAbsent(index, i->{
				var img=render(i);
				async(()->{
					for(int j=Math.max(0, i-2);j<Math.min(snapshots.size(), i+2);j++){
						cache.computeIfAbsent(i, this::render);
					}
				});
				return img;
			});
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
				
				var lab=new JLabel(new ImageIcon(img)){
					@Override
					public void paint(Graphics g){
						super.paint(g);
						g.setColor(Color.WHITE);
						g.setFont(new Font(Font.MONOSPACED, Font.BOLD, pixelScale*2));
						var pp=pixelScale*3;
						var x =pos[0];
						var y =pos[1];
						var g2=(Graphics2D)g;
						g2.setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_ON);
						var width=getImg(safePos()).getWidth();
						g.drawString(""+(x/pp+y/pp*width/pp), pos[0]/pp*pp, pos[1]/pp*pp+pp/2);
					}
				};
				
				target.removeAll();
				target.add(lab);
				
				target.revalidate();
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
			if(i==0){
				setGUIImg(getImg(0));
				
				getJFrame().requestFocus();
			}
		}
		
		@Override
		public void finish(){}
	}
	
	boolean doAll();
	
	void snapshot(Snapshot snap) throws IOException;
	
	void finish();
	
}
