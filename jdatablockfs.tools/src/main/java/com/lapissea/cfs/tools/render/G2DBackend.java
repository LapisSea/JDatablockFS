package com.lapissea.cfs.tools.render;

import com.lapissea.cfs.tools.DrawFont;
import imgui.ImGui;

import javax.swing.*;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
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
import java.util.stream.Collectors;

import static java.awt.event.MouseEvent.BUTTON1;
import static java.awt.event.MouseEvent.BUTTON2;
import static org.joml.Math.clamp;

public class G2DBackend extends RenderBackend{
	
	static{
		ImGuiUtils.load();
	}
	
	private BufferedImage displayBuffer, activeBuffer;
	private BufferedImage b1, b2;
	private Graphics2D currentGraphics;
	
	private final Deque<AffineTransform> transformStack = new LinkedList<>();
	
	private final DisplayInterface displayInterface;
	
	private final JFrame frame;
	private final JPanel panel;
	
	private int mouseX;
	private int mouseY;
	
	private final EnumSet<DisplayInterface.MouseKey> mouseDowns = EnumSet.noneOf(DisplayInterface.MouseKey.class);
	
	private       Thread          renderThread;
	private final Deque<Runnable> tasks = new LinkedList<>();
	
	private ImGuiImplG2D imguiImpl;
	
	private final DrawFont font = new DrawFont(){
		@Override
		public void fillStrings(List<StringDraw> strings){
			for(List<StringDraw> batch : strings.size()<=1? List.of(strings) : strings.stream().collect(Collectors.groupingBy(StringDraw::pixelHeight)).values()){
				var pixelHeight = batch.get(0).pixelHeight();
				currentGraphics.setFont(currentGraphics.getFont().deriveFont(pixelHeight*0.8F));
				for(StringDraw sd : batch){
					var col = alphaScale(sd.color(), pixelHeight, false);
					if(col.getAlpha()<2) continue;
					
					var t = currentGraphics.getTransform();
					currentGraphics.translate(sd.x(), sd.y());
					currentGraphics.scale(sd.xScale(), 1);
					
					currentGraphics.setColor(col);
					currentGraphics.drawString(sd.string(), 0, 0);
					
					currentGraphics.setTransform(t);
				}
			}
		}
		@Override
		public void outlineStrings(List<StringDraw> strings){
			
			for(List<StringDraw> batch : strings.size()<=1? List.of(strings) : strings.stream().collect(Collectors.groupingBy(StringDraw::pixelHeight)).values()){
				var pixelHeight = batch.get(0).pixelHeight();
				currentGraphics.setFont(currentGraphics.getFont().deriveFont(pixelHeight*0.8F));
				
				setStrokeWidth(1);
				for(StringDraw sd : batch){
					var col = alphaScale(sd.color(), pixelHeight, true);
					if(col.getAlpha()<2) continue;
					currentGraphics.setColor(col);
					var transform = new AffineTransform();
					transform.translate(sd.x(), sd.y());
					transform.scale(sd.xScale(), 1);
					currentGraphics.draw(new TextLayout(sd.string(), currentGraphics.getFont(), currentGraphics.getFontRenderContext()).getOutline(transform));
				}
			}
		}
		@Override
		public Bounds getStringBounds(String string, float fontScale){
			currentGraphics.setFont(currentGraphics.getFont().deriveFont(fontScale/2));
			var rect = currentGraphics.getFontMetrics().getStringBounds(string, currentGraphics);
			return new DrawFont.Bounds((float)(rect.getWidth()), (float)(rect.getHeight()));
		}
		@Override
		public boolean canFontDisplay(char c){
			return currentGraphics.getFont().canDisplay(c);
		}
		
		private Color alphaScale(Color color, float pixelHeight, boolean outline){
			var   minSpace = outline? 20 : 5;
			float alphaMul = clamp(0, 1, (pixelHeight - minSpace)/3);
			var   newCol   = new Color(color.getRed(), color.getGreen(), color.getBlue(), (int)(color.getAlpha()*alphaMul));
			return newCol;
		}
	};
	
	private static DisplayInterface.MouseKey getKey(MouseEvent e){
		return switch(e.getButton()){
			case BUTTON1 -> DisplayInterface.MouseKey.LEFT;
			case BUTTON2 -> DisplayInterface.MouseKey.RIGHT;
			default -> {
				throw new RuntimeException("Unknown event" + e);
			}
		};
	}
	
	public G2DBackend(){
		frame = new JFrame();
		imguiImpl = ImGuiUtils.makeG2DImpl();
		
		File f = new File("wind");
		try(var in = new BufferedReader(new FileReader(f))){
			frame.setLocation(Integer.parseInt(in.readLine()), Integer.parseInt(in.readLine()));
			frame.setSize(Integer.parseInt(in.readLine()), Integer.parseInt(in.readLine()));
		}catch(Throwable ignored){
			frame.setSize(800, 800);
			frame.setLocationRelativeTo(null);
		}
		
		panel = new JPanel(){
			@Override
			public void update(Graphics g){
				paint((Graphics2D)g);
			}
			@Override
			public void paint(Graphics g){
				paint((Graphics2D)g);
			}
			
			public void paint(Graphics2D g){
				if(displayBuffer == null) return;
				g.drawImage(displayBuffer, 0, 0, panel.getWidth(), panel.getHeight(), null);
			}
		};
		
		frame.setContentPane(panel);
		frame.setVisible(true);
		frame.createBufferStrategy(2);
		
		panel.addMouseMotionListener(new MouseMotionAdapter(){
			public void ev(MouseEvent e){
				mouseX = e.getX();
				mouseY = e.getY();
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
		
		displayInterface = new DisplayInterface(){
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
			@Override
			public boolean isFocused(){
				return frame.isFocused();
			}
			@Override
			public int getPositionX(){
				return frame.getLocationOnScreen().x;
			}
			@Override
			public int getPositionY(){
				return frame.getLocationOnScreen().y;
			}
		};
		markFrameDirty();
	}
	
	@Override
	public void start(Runnable start){
		renderThread = Thread.ofPlatform().name("display").daemon().start(start);
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
		var col = readColor();
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
		if(Thread.currentThread() != renderThread) throw new IllegalStateException();
		if(currentGraphics != null) throw new IllegalStateException();
		
		flushTasks();
		
		int w = getDisplay().getWidth();
		int h = getDisplay().getHeight();
		if(b1 == null || b1.getWidth() != w || b1.getHeight() != h){
			b1 = panel.getGraphicsConfiguration().createCompatibleImage(w, h, Transparency.OPAQUE);
		}
		
		activeBuffer = b1;
		
		var tmp = b1;
		b1 = b2;
		b2 = tmp;
		
		currentGraphics = activeBuffer.createGraphics();
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
		var data = ImGui.getDrawData();
		if(data.ptr != 0) imguiImpl.renderDrawData(currentGraphics, data);
		
		currentGraphics.dispose();
		currentGraphics = null;
		
		displayBuffer = activeBuffer;
		
		panel.repaint();
	}
	@Override
	public DrawFont getFont(){
		return font;
	}
	@Override
	public void runLater(Runnable task){
		if(Thread.currentThread() == renderThread){
			task.run();
		}else{
			synchronized(tasks){
				tasks.add(task);
			}
		}
	}
}
