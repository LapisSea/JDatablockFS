package com.lapissea.cfs.tools.render;

import com.lapissea.cfs.tools.DrawFont;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

import static java.awt.event.MouseEvent.BUTTON1;
import static java.awt.event.MouseEvent.BUTTON2;
import static org.joml.Math.clamp;

public class G2DBackend extends RenderBackend{
	static{
		System.setProperty("sun.java2d.opengl", "true");
	}
	
	private BufferedImage displayBuffer, activeBuffer;
	private BufferedImage b1, b2;
	private Graphics2D currentGraphics;
	
	private final Deque<AffineTransform> transformStack=new LinkedList<>();
	
	private final DisplayInterface displayInterface;
	
	private final JFrame frame;
	private final JPanel panel;
	
	private int mouseX;
	private int mouseY;
	
	private final EnumSet<DisplayInterface.MouseKey> mouseDowns=EnumSet.noneOf(DisplayInterface.MouseKey.class);
	
	private       Thread          renderThread;
	private final Deque<Runnable> tasks=new LinkedList<>();
	
	private final DrawFont font=new DrawFont(){
		@Override
		public void fillStrings(List<StringDraw> strings){
			for(StringDraw string : strings){
				fillString(string.color(), string.string(), string.x(), string.y(), string.pixelHeight());
			}
		}
		@Override
		public void outlineStrings(List<StringDraw> strings){
			for(StringDraw string : strings){
				outlineString(string.color(), string.string(), string.x(), string.y());
			}
		}
		@Override
		public Bounds getStringBounds(String string){
			currentGraphics.setFont(currentGraphics.getFont().deriveFont(getFontScale()/2));
			var rect=currentGraphics.getFontMetrics().getStringBounds(string, currentGraphics);
			return new DrawFont.Bounds((float)(rect.getWidth()), (float)(rect.getHeight()));
		}
		@Override
		public boolean canFontDisplay(char c){
			return currentGraphics.getFont().canDisplay(c);
		}
		
		public void fillString(Color color, String str, float x, float y, float pixelHeight){
			var   outline=false;
			Color newCol =alphaScale(color, pixelHeight, outline);
			currentGraphics.setColor(newCol);
			currentGraphics.setFont(currentGraphics.getFont().deriveFont(getFontScale()/2));
			currentGraphics.drawString(str, x, y);
		}
		private Color alphaScale(Color color, float pixelHeight, boolean outline){
			var   minSpace=outline?20:5;
			float alphaMul=clamp(0, 1, (pixelHeight-minSpace)/3);
			var   newCol  =new Color(color.getRed(), color.getGreen(), color.getBlue(), (int)(color.getAlpha()*alphaMul));
			return newCol;
		}
		public void outlineString(Color color, String str, float x, float y){
			currentGraphics.setColor(color);
			setStrokeWidth(1);
			currentGraphics.setFont(currentGraphics.getFont().deriveFont(getFontScale()/2));
			var transform=new AffineTransform();
			transform.translate(x, y);
			Shape shape=new TextLayout(str, currentGraphics.getFont(), currentGraphics.getFontRenderContext()).getOutline(transform);
			currentGraphics.draw(shape);
		}
	};
	
	private static DisplayInterface.MouseKey getKey(MouseEvent e){
		return switch(e.getButton()){
			case BUTTON1 -> DisplayInterface.MouseKey.LEFT;
			case BUTTON2 -> DisplayInterface.MouseKey.RIGHT;
			default -> {
				throw new RuntimeException("Unknown event"+e);
			}
		};
	}
	
	public G2DBackend(){
		frame=new JFrame();
		
		File f=new File("wind");
		try(var in=new BufferedReader(new FileReader(f))){
			frame.setLocation(Integer.parseInt(in.readLine()), Integer.parseInt(in.readLine()));
			frame.setSize(Integer.parseInt(in.readLine()), Integer.parseInt(in.readLine()));
		}catch(Throwable ignored){
			frame.setSize(800, 800);
			frame.setLocationRelativeTo(null);
		}
		
		panel=new JPanel(){
			@Override
			public void update(Graphics g){
				paint((Graphics2D)g);
			}
			@Override
			public void paint(Graphics g){
				paint((Graphics2D)g);
			}
			
			public void paint(Graphics2D g){
				if(displayBuffer==null) return;
				g.drawImage(displayBuffer, 0, 0, panel.getWidth(), panel.getHeight(), null);
			}
		};
		
		frame.setContentPane(panel);
		frame.setVisible(true);
		frame.createBufferStrategy(2);
		
		panel.addMouseMotionListener(new MouseMotionAdapter(){
			public void ev(MouseEvent e){
				mouseX=e.getX();
				mouseY=e.getY();
			}
			@Override
			public void mouseMoved(MouseEvent e){
				ev(e);
			}
			@Override
			public void mouseDragged(MouseEvent e){
				ev(e);
			}
		});
		panel.addMouseListener(new MouseAdapter(){
			@Override
			public void mousePressed(java.awt.event.MouseEvent e){
				mouseDowns.add(getKey(e));
			}
			@Override
			public void mouseReleased(java.awt.event.MouseEvent e){
				mouseDowns.remove(getKey(e));
			}
		});
		
		displayInterface=new DisplayInterface(){
			@Override
			public int getWidth(){
				return panel.getWidth();
			}
			@Override
			public int getHeight(){
				return panel.getHeight();
			}
			@Override
			public int getMouseX(){
				return mouseX;
			}
			@Override
			public int getMouseY(){
				return mouseY;
			}
			
			@Override
			public void registerDisplayResize(Runnable listener){
				frame.addComponentListener(new ComponentAdapter(){
					@Override
					public void componentResized(ComponentEvent e){
						listener.run();
					}
				});
			}
			
			@Override
			public void registerKeyboardButton(Consumer<KeyboardEvent> listener){
				frame.addKeyListener(new KeyAdapter(){
					public void ev(KeyEvent e, ActionType typ){
						//TODO: fix GLFW/Swing codes
						listener.accept(new KeyboardEvent(typ, e.getKeyCode()));
					}
					@Override
					public void keyPressed(KeyEvent e){
						ev(e, ActionType.DOWN);
					}
					@Override
					public void keyReleased(KeyEvent e){
						ev(e, ActionType.UP);
					}
				});
			}
			@Override
			public void registerMouseButton(Consumer<MouseEvent> listener){
				panel.addMouseListener(new MouseAdapter(){
					public void ev(java.awt.event.MouseEvent e, ActionType type){
						listener.accept(new MouseEvent(getKey(e), type));
					}
					@Override
					public void mousePressed(java.awt.event.MouseEvent e){
						ev(e, ActionType.DOWN);
					}
					@Override
					public void mouseReleased(java.awt.event.MouseEvent e){
						ev(e, ActionType.UP);
					}
				});
			}
			@Override
			public void registerMouseScroll(Consumer<Integer> listener){
				panel.addMouseListener(new MouseAdapter(){
					@Override
					public void mouseWheelMoved(MouseWheelEvent e){
						listener.accept(e.getY());
					}
				});
			}
			
			@Override
			public void registerMouseMove(Runnable listener){
				panel.addMouseMotionListener(new MouseAdapter(){
					@Override
					public void mouseDragged(java.awt.event.MouseEvent e){
						listener.run();
					}
					@Override
					public void mouseMoved(java.awt.event.MouseEvent e){
						listener.run();
					}
				});
			}
			
			@Override
			public boolean isMouseKeyDown(MouseKey key){
				return mouseDowns.contains(key);
			}
			
			@Override
			public boolean isOpen(){
				return frame.isDisplayable();
			}
			@Override
			public void requestClose(){
				frame.setVisible(false);
			}
			@Override
			public void pollEvents(){
			}
			@Override
			public void destroy(){
				frame.dispose();
			}
			@Override
			public void setTitle(String title){
				frame.setTitle(title);
			}
		};
		markFrameDirty();
	}
	
	@Override
	public void start(Runnable start){
		renderThread=new Thread(start, "display");
		renderThread.setDaemon(false);
		renderThread.start();
	}
	
	@Override
	public BulkDraw bulkDraw(DrawMode mode){
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
	public DisplayInterface getDisplay(){
		return displayInterface;
	}
	
	@Override
	public void fillQuad(double x, double y, double width, double height){
		currentGraphics.fill(new Rectangle2D.Double(x, y, width, height));
	}
	
	@Override
	public void outlineQuad(double x, double y, double width, double height){
		setStrokeWidth(getLineWidth());
		currentGraphics.draw(new Rectangle2D.Double(x, y, width, height));
	}
	private void setStrokeWidth(float i){
		currentGraphics.setStroke(new BasicStroke(i, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
	}
	
	@Override
	public void drawLine(double xFrom, double yFrom, double xTo, double yTo){
		setStrokeWidth(getLineWidth());
		
		currentGraphics.draw(
			new Line2D.Double(
				xFrom, yFrom,
				xTo, yTo
			)
		);
	}
	
	@Override
	public void setColor(Color color){
		currentGraphics.setColor(color);
	}
	@Override
	public void pushMatrix(){
		transformStack.push(currentGraphics.getTransform());
	}
	@Override
	public void popMatrix(){
		currentGraphics.setTransform(transformStack.pop());
	}
	@Override
	public void translate(double x, double y){
		currentGraphics.translate(x, y);
	}
	@Override
	public Color readColor(){
		return currentGraphics.getColor();
	}
	@Override
	public void initRenderState(){
		transformStack.clear();
		currentGraphics.setTransform(new AffineTransform());
	}
	@Override
	public void clearFrame(){
		var col=readColor();
		currentGraphics.setColor(Color.GRAY);
		currentGraphics.fillRect(0, 0, getDisplay().getWidth(), getDisplay().getHeight());
		currentGraphics.setColor(col);
	}
	@Override
	public void scale(double x, double y){
		currentGraphics.scale(x, y);
	}
	@Override
	public void rotate(double angle){
		currentGraphics.rotate(angle);
	}
	@Override
	public void preRender(){
		assert Thread.currentThread()==renderThread;
		assert currentGraphics==null;
		
		flushTasks();
		
		int w=getDisplay().getWidth();
		int h=getDisplay().getHeight();
		if(b1==null||b1.getWidth()!=w||b1.getHeight()!=h){
			b1=panel.getGraphicsConfiguration().createCompatibleImage(w, h, Transparency.OPAQUE);
		}
		
		activeBuffer=b1;
		
		var tmp=b1;
		b1=b2;
		b2=tmp;
		
		currentGraphics=activeBuffer.createGraphics();
		currentGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		currentGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		currentGraphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
	}
	private void flushTasks(){
		synchronized(tasks){
			while(!tasks.isEmpty()){
				tasks.removeFirst().run();
			}
		}
	}
	
	@Override
	public void postRender(){
		currentGraphics.dispose();
		currentGraphics=null;
		
		displayBuffer=activeBuffer;
		
		panel.repaint();
	}
	@Override
	public DrawFont getFont(){
		return font;
	}
	@Override
	public void runLater(Runnable task){
		if(Thread.currentThread()==renderThread){
			task.run();
		}else{
			synchronized(tasks){
				tasks.add(task);
			}
		}
	}
}
