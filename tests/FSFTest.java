import com.lapissea.fsf.FileSystemInFile;
import com.lapissea.fsf.IOInterface;
import com.lapissea.util.LogUtil;
import com.lapissea.util.MathUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeRunnable;
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
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.lapissea.util.UtilL.*;
import static javax.swing.SwingUtilities.*;

class FSFTest{
	static{
//		LogUtil.Init.attach(0);
		LogUtil.Init.attach(LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER);
	}
	
	public static void main(String[] args) throws Exception{
		
		for(File file : new File(".").listFiles()){
			file.delete();
		}
		var pixelScale=12;
		
		var     encoderArr=new SequenceEncoder[1];
		var     jframe    =new JFrame[1];
		var     jfb       =new ArrayList<BufferedImage>();
		var     jft       =new ArrayList<Throwable>();
		float[] imgPos    ={0};
		int[]   pos       ={0, 0};
		
		class T<t>{
			t t;
		}
		
		T<Consumer<BufferedImage>> setGUIImg0=new T();
		
		Supplier<JFrame> getJFrame=()->{
			if(jframe[0]==null){
				
				var jf=new JFrame();
				
				jf.setVisible(true);
				jf.setLocationRelativeTo(null);
				jf.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
				jf.addComponentListener(new ComponentAdapter(){
					@Override
					public void componentResized(ComponentEvent e){
						jf.setLocationRelativeTo(null);
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
						imgPos[0]=MathUtil.snap(jfb.size()*(e.getX()/(float)jf.getWidth()), 0, jfb.size()-1);
						setGUIImg0.t.accept(jfb.get(MathUtil.snap((int)imgPos[0], 0, jfb.size()-1)));
						mouseMoved(e);
					}
					
					@Override
					public void mouseWheelMoved(MouseWheelEvent e){
						imgPos[0]=(float)MathUtil.snap(e.getPreciseWheelRotation()+imgPos[0], 0, jfb.size());
						setGUIImg0.t.accept(jfb.get(MathUtil.snap((int)imgPos[0], 0, jfb.size()-1)));
					}
					
					@Override
					public void mouseClicked(MouseEvent e){
						jft.get(MathUtil.snap((int)imgPos[0], 0, jfb.size()-1)).printStackTrace();
					}
				};
				
				jf.addMouseMotionListener(adp);
				jf.addMouseWheelListener(adp);
				jf.addMouseListener(adp);
				
				jf.setResizable(false);
				jf.requestFocus();
				jframe[0]=jf;
			}
			return jframe[0];
		};
		
		
		setGUIImg0.t=img->invokeLater(()->{
			var jf=getJFrame.get();
			
			var lab=new JLabel(new ImageIcon(img)){
				@Override
				public void paint(Graphics g){
					super.paint(g);
					g.setColor(Color.WHITE);
					g.setFont(new Font(Font.MONOSPACED, Font.BOLD, pixelScale*2));
					var pp=pixelScale*3;
					var x =pos[0];
					var y =pos[1];
					g.drawString(""+(x/pp+y/pp*jf.getContentPane().getWidth()/pp), pos[0]/pp*pp, pos[1]/pp*pp+pp/2);
				}
			};
			
			jf.setContentPane(lab);
			jf.pack();
		});
		Consumer<BufferedImage> setGUIImg=setGUIImg0.t;
		
		Supplier<SequenceEncoder> encoder=()->{
			if(encoderArr[0]==null){
				try{
					encoderArr[0]=new SequenceEncoder(NIOUtils.writableChannel(new File("output.mp4")), Rational.R(60, 1), Format.MOV, Codec.H264, null);
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
			return encoderArr[0];
		};
		
		int[] counter={1};
		var   pool   =(ThreadPoolExecutor)Executors.newFixedThreadPool(1);
		
		Function<BufferedImage, BufferedImage> noAlpha=img->{
			BufferedImage copy=new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D    g2d =copy.createGraphics();
			g2d.setColor(Color.DARK_GRAY);
			g2d.fillRect(0, 0, copy.getWidth(), copy.getHeight());
			g2d.drawImage(img, 0, 0, null);
			g2d.dispose();
			return copy;
		};
		
		UnsafeConsumer<BufferedImage, IOException> doMp4=img->encoder.get().encodeNativeFrame(AWTUtil.fromBufferedImageRGB(noAlpha.apply(img)));
		UnsafeConsumer<BufferedImage, IOException> doPng=img->ImageIO.write(img, "png", new File("snap"+(counter[0]++)+".png"));
		UnsafeConsumer<BufferedImage, IOException> doJpg=img->ImageIO.write(noAlpha.apply(img), "jpg", new File("snap"+(counter[0]++)+".jpg"));
		UnsafeConsumer<BufferedImage, IOException> doGui=img->{
			img=noAlpha.apply(img);
			
			jfb.add(img);
			
			imgPos[0]=jfb.size()-1;
			setGUIImg.accept(img);
		};
		
		UnsafeConsumer<BufferedImage, IOException> writer=doGui;
		
		UnsafeConsumer<BufferedImage, IOException> writeImg=img->{
			jft.add(new Throwable());
//			pool.submit(()->{
			try{
				writer.accept(img);
			}catch(Exception ex){
				ex.printStackTrace();
			}
//			});
		};
		
		try{
			var source=new IOInterface.MemoryRA();
			var fil   =new FileSystemInFile(source);
			
			
			UnsafeConsumer<long[], IOException> snapshotIds=ids->{
				var img=fil.renderFile(24, 24, ids, pixelScale);
				writeImg.accept(img);
			};
			UnsafeRunnable<IOException> snapshot=()->{};
			
			if(writer!=null){
				boolean moreLog=List.of(doMp4, doGui).contains(writer);
				
				if(moreLog) source.onWrite=snapshotIds;
				snapshot=()->snapshotIds.accept(new long[0]);
			}
			
			snapshot.run();
			
			var testFile=fil.createFile("test", 8);
			try(OutputStream os=testFile.write()){
				os.write("THIS WORKS!!!!".getBytes());
			}
			
			var s2=fil.getFile("test").readAllString();
			Assert(s2.equals("THIS WORKS!!!!"), s2);
			
			snapshot.run();
			
			try(OutputStream os=fil.getFile("aaaaaaa").write()){
				os.write("THIS REALLY WORKS!!!!".getBytes());
			}
			snapshot.run();
			
			var s0=fil.getFile("aaaaaaa").readAllString();
			Assert(s0.equals("THIS REALLY WORKS!!!!"), s0);
			
			testFile.writeAll("THIS W".getBytes());
			snapshot.run();
			testFile.writeAll("THIS WORKS!!!!".getBytes());
			testFile.writeAll("THIS W".getBytes());
			testFile.writeAll("THIS WORKS!!!!".getBytes());
			testFile.writeAll("THIS W".getBytes());
			testFile.writeAll("this works????".getBytes());
			snapshot.run();
			fil.createFile("test3", 8).writeAll("12345678B".getBytes());
			snapshot.run();
			fil.createFile("test4", 8).writeAll("123456789".getBytes());
			snapshot.run();
			fil.createFile("a5", 8).writeAll("12345678".getBytes());
			snapshot.run();
			fil.getFile("test3").writeAll("12345678".getBytes());
			snapshot.run();
			fil.getFile("test4").writeAll("12345678".getBytes());
			snapshot.run();
			fil.getFile("test3").writeAll("Ur mum very gay. Like very GAY. Ur mum so gay she make the gay not gay.".getBytes());
			var s1=fil.getFile("test3").readAllString();
			Assert(s1.equals("Ur mum very gay. Like very GAY. Ur mum so gay she make the gay not gay."), s1);
			snapshot.run();
			
			
			fil.defragment();
			snapshot.run();
			LogUtil.println(fil.getFile("test3").readAllString());
			if(true) return;
			
			LogUtil.println(testFile.readAllString());
			
			fil.delete("");
			
			LogUtil.println(fil.getFile("test2").readAllString());
			
			fil.getFile("test").writeAll("This is a very very very fucking long text that will force the file to grow.".getBytes());
			LogUtil.println(fil.getFile("test").readAllString());
			
			fil.getFile("test").writeAll("This is a bit smaller text that will force the file to grow shrink.".getBytes());
			LogUtil.println(fil.getFile("test").readAllString());
			
			fil.getFile("test").writeAll("This is small.".getBytes());
			LogUtil.println(fil.getFile("test").readAllString());
			
			fil.delete("");
			System.exit(0);
			
			fil.delete("test");
			
			fil.getFile("test");
		}catch(Throwable e){
			e.printStackTrace();
		}finally{
			UtilL.sleepWhile(()->pool.getTaskCount()>pool.getCompletedTaskCount(), 50);
			if(encoderArr[0]!=null) encoderArr[0].finish();
		}
	}
	
}
