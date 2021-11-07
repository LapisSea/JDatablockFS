package com.lapissea.cfs.tools;

import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.MemFrame;
import com.lapissea.util.MathUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import java.util.*;
import java.util.function.IntConsumer;

import static javax.swing.SwingUtilities.*;

@SuppressWarnings("AutoBoxing")
public class Display2D extends BinaryDrawing implements DataLogger{
	
	private static class Session implements DataLogger.Session{
		private final List<CachedFrame> frames=new ArrayList<>();
		private       int               pos;
		
		private final IntConsumer onFrameChange;
		private final Runnable    setDirty;
		private       boolean     markForDeletion;
		
		private Session(Runnable setDirty, IntConsumer onFrameChange){
			this.setDirty=setDirty;
			this.onFrameChange=onFrameChange;
		}
		
		@Override
		public synchronized void log(MemFrame frame){
			frames.add(new CachedFrame(frame, new ParsedFrame(frames.size())));
			setPos(frames.size()-1);
		}
		
		@Override
		public void finish(){}
		
		@Override
		public void reset(){
			frames.clear();
			setPos(0);
			setDirty.run();
		}
		@Override
		public void delete(){
			markForDeletion=true;
		}
		public void setPos(int pos){
			if(this.pos==pos) return;
			
			this.pos=pos;
			setDirty.run();
			onFrameChange.accept(getPos());
		}
		
		public int getPos(){
			return pos;
		}
	}
	
	private Graphics2D             currentGraphics;
	private Deque<AffineTransform> transformStack=new LinkedList<>();
	
	private class Pan extends Panel{
		
		public int mouseX;
		public int mouseY;
		
		@Override
		public void update(Graphics g){
			paint((Graphics2D)g);
		}
		@Override
		public void paint(Graphics g){
			paint((Graphics2D)g);
		}
		
		public void paint(Graphics2D g){
			try{
				cleanUpSessions();
				
				if(visibleSession!=activeSession){
					visibleSession=activeSession;
					shouldRerender=true;
				}
				
				if(visibleSession.isEmpty()||visibleSession.get().frames.isEmpty()) return;
				
				var image=render;
				
				if(shouldRerender){
					shouldRerender=false;
					
					if(image==null||image.getWidth()!=getWidth()||image.getHeight()!=getHeight()){
						image=getGraphicsConfiguration().createCompatibleImage(getWidth(), getHeight(), Transparency.TRANSLUCENT);
						render=image;
					}
					
					currentGraphics=image.createGraphics();
					currentGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					currentGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
					currentGraphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
					try{
						render();
					}catch(Throwable e){
						new RuntimeException("Failed to complete frame render", e).printStackTrace();
					}
					
					currentGraphics.dispose();
					currentGraphics=null;
					
				}
				
				g.drawImage(image, 0, 0, null);
			}catch(Throwable e){
				e.printStackTrace();
			}
		}
	}
	
	private float pixelsPerByte=300;
	
	private final Map<String, Session> sessions      =new HashMap<>();
	private       Optional<Session>    activeSession =Optional.empty();
	private       Optional<Session>    visibleSession=Optional.empty();
	private final Frame                frame         =new Frame();
	private final Pan                  pan;
	
	public Display2D(){
		File f=new File("wind");
		
		try(var in=new BufferedReader(new FileReader(f))){
			frame.setLocation(Integer.parseInt(in.readLine()), Integer.parseInt(in.readLine()));
			frame.setSize(Integer.parseInt(in.readLine()), Integer.parseInt(in.readLine()));
		}catch(Throwable ignored){
			frame.setSize(800, 800);
			frame.setLocationRelativeTo(null);
		}
		
		pan=new Pan();
		pan.setBackground(Color.GRAY);
		frame.setLayout(new BorderLayout());
		frame.add(pan);
		
		frame.addWindowListener(
			new WindowAdapter(){
				@Override
				public void windowClosed(WindowEvent e){System.exit(0);}
			}
		);
		
		frame.addKeyListener(new KeyAdapter(){
			@Override
			public void keyPressed(KeyEvent e){
				cleanUpSessions();
				visibleSession.ifPresent(ses->{
					if(e.getKeyChar()=='a'||e.getKeyCode()==37) ses.setPos(ses.getPos()-1);
					if(e.getKeyChar()=='d'||e.getKeyCode()==39) ses.setPos(ses.getPos()+1);
					ses.setPos(MathUtil.snap(ses.getPos(), 0, ses.frames.size()-1));
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
				});
			}
		});
		
		frame.addComponentListener(new ComponentAdapter(){
			@Override
			public void componentResized(ComponentEvent e){
				if(visibleSession.isPresent()){
					calcSize(getFrame(getFramePos()).data().data().length, true);
				}
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
					}catch(Throwable ignored){}
				}
			}
		});
		
		pan.addMouseMotionListener(new MouseMotionAdapter(){
			@Override
			public void mouseMoved(MouseEvent e){
				pan.mouseX=e.getX();
				pan.mouseY=e.getY();
				shouldRerender=true;
				pan.repaint();
			}
			
			@Override
			public void mouseDragged(MouseEvent e){
				pan.mouseX=e.getX();
				pan.mouseY=e.getY();
				
				int width=pan.getWidth();
				int x    =MathUtil.snap(pan.mouseX, 0, width);
				
				float val=x/(float)width;
				
				cleanUpSessions();
				visibleSession.ifPresent(ses->ses.setPos((int)(val*(ses.frames.size()-1))));
				
				pan.repaint();
			}
		});
		
		pan.addMouseListener(new MouseAdapter(){
			@Override
			public void mouseClicked(MouseEvent e){
				cleanUpSessions();
				visibleSession.ifPresent(ses->{
					if(ses.frames.isEmpty()) return;
					ses.frames.get(ses.getPos()).data().printStackTrace();
					frame.repaint();
				});
			}
		});
		
		frame.setVisible(true);
//		frame.createBufferStrategy(2);
	}
	
	boolean       shouldRerender=true;
	BufferedImage render        =null;
	
	private void cleanUpSessions(){
		sessions.values().removeIf(s->s.markForDeletion);
		activeSession.filter(s->s.markForDeletion).ifPresent(s->activeSession=sessions.values().stream().findAny());
	}
	
	@Override
	protected BulkDraw bulkDraw(DrawMode mode){
		return new BulkDraw(mode){
			@Override
			protected void start(DrawMode mode){
			}
			@Override
			protected void end(){
			}
		};
	}
	@Override
	protected void fillQuad(double x, double y, double width, double height){
		currentGraphics.fill(new Rectangle2D.Double(x, y, width, height));
	}
	
	@Override
	protected void outlineQuad(double x, double y, double width, double height){
		setStrokeWidth(getLineWidth());
		currentGraphics.draw(new Rectangle2D.Double(x, y, width, height));
	}
	
	@Override
	protected float getPixelsPerByte(){
		return pixelsPerByte;
	}
	
	@Override
	protected void drawLine(double xFrom, double yFrom, double xTo, double yTo){
		setStrokeWidth(getLineWidth());
		
		currentGraphics.draw(
			new Line2D.Double(
				xFrom*pixelsPerByte, yFrom*pixelsPerByte,
				xTo*pixelsPerByte, yTo*pixelsPerByte
			)
		);
	}
	
	@Override
	protected int getWidth(){
		return pan.getWidth();
	}
	@Override
	protected int getHeight(){
		return pan.getHeight();
	}
	@Override
	protected int getMouseX(){
		return pan.mouseX;
	}
	@Override
	protected int getMouseY(){
		return pan.mouseY;
	}
	@Override
	protected void pixelsPerByteChange(float newPixelsPerByte){
		shouldRerender=true;
		pixelsPerByte=newPixelsPerByte;
	}
	@Override
	protected void setColor(Color color){
		currentGraphics.setColor(color);
	}
	@Override
	protected void pushMatrix(){
		transformStack.push(currentGraphics.getTransform());
	}
	@Override
	protected void popMatrix(){
		currentGraphics.setTransform(transformStack.pop());
	}
	@Override
	protected GLFont.Bounds getStringBounds(String str){
		currentGraphics.setFont(currentGraphics.getFont().deriveFont(getFontScale()/2));
		var rect=currentGraphics.getFontMetrics().getStringBounds(str, currentGraphics);
		return new GLFont.Bounds((float)(rect.getWidth()), (float)(rect.getHeight()));
	}
	@Override
	protected void translate(double x, double y){
		currentGraphics.translate(x, y);
	}
	@Override
	protected Color readColor(){
		return currentGraphics.getColor();
	}
	@Override
	protected void initRenderState(){
		transformStack.clear();
		currentGraphics.setTransform(new AffineTransform());
	}
	@Override
	protected void clearFrame(){
		var col=readColor();
		currentGraphics.setColor(Color.GRAY);
		currentGraphics.fillRect(0, 0, getWidth(), getHeight());
		currentGraphics.setColor(col);
	}
	@Override
	protected boolean isWritingFilter(){
		return false;
	}
	@Override
	protected String getFilter(){
		return "";
	}
	@Override
	protected void scale(double x, double y){
		currentGraphics.scale(x, y);
	}
	@Override
	protected void rotate(double angle){
		currentGraphics.rotate(angle);
	}
	@Override
	protected void outlineString(String str, float x, float y){
		setStrokeWidth(1);
		currentGraphics.setFont(currentGraphics.getFont().deriveFont(getFontScale()/2));
		var transform=new AffineTransform();
		transform.translate(x, y);
		Shape shape=new TextLayout(str, currentGraphics.getFont(), currentGraphics.getFontRenderContext()).getOutline(transform);
		currentGraphics.draw(shape);
	}
	private void setStrokeWidth(float i){
		currentGraphics.setStroke(new BasicStroke(i, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
	}
	@Override
	protected void fillString(String str, float x, float y){
		currentGraphics.setFont(currentGraphics.getFont().deriveFont(getFontScale()/2));
		currentGraphics.drawString(str, x, y);
	}
	@Override
	protected boolean canFontDisplay(char c){
		return currentGraphics.getFont().canDisplay(c);
	}
	@Override
	protected int getFrameCount(){
		return visibleSession.map(ses->ses.frames.size()).orElse(0);
	}
	@Override
	protected CachedFrame getFrame(int index){
		return visibleSession.map(ses->ses.frames.get(index)).orElse(null);
	}
	@Override
	protected int getFramePos(){
		return visibleSession.map(ses->ses.pos).orElse(0);
	}
	@Override
	protected void preRender(){
	}
	@Override
	protected void postRender(){
	}
	
	@Override
	public DataLogger.Session getSession(String name){
		var ses=sessions.computeIfAbsent(
			name,
			nam->new Session(()->{
				frame.repaint();
				shouldRerender=true;
				invokeLater(pan::repaint);
			}, frame->{
				this.frame.repaint();
				shouldRerender=true;
				this.frame.setTitle("Binary display - frame: "+frame+" @"+name);
				invokeLater(pan::repaint);
			})
		);
		activeSession=Optional.of(ses);
		invokeLater(pan::repaint);
		return ses;
	}
	
	@Override
	public void destroy(){
		invokeLater(()->{
			frame.setVisible(false);
			frame.dispose();
		});
		sessions.values().forEach(Session::finish);
		activeSession=Optional.empty();
		visibleSession=Optional.empty();
		sessions.clear();
	}
}
