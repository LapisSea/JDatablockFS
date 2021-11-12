package com.lapissea.cfs.tools.render;

import com.lapissea.cfs.tools.GLFont;
import com.lapissea.util.NotImplementedException;

import java.awt.*;
import java.awt.event.*;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.function.Consumer;

import static java.awt.event.MouseEvent.BUTTON1;
import static java.awt.event.MouseEvent.BUTTON2;
import static javax.swing.SwingUtilities.invokeLater;

public class G2DBackend extends RenderBackend{
	
	private BufferedImage render;
	private Graphics2D    currentGraphics;
	
	private final Deque<AffineTransform> transformStack=new LinkedList<>();
	
	private final DisplayInterface displayInterface;
	
	private final Panel target;
	private       int   mouseX;
	private       int   mouseY;
	
	private EnumSet<DisplayInterface.MouseKey> mouseDowns=EnumSet.noneOf(DisplayInterface.MouseKey.class);
	
	private static DisplayInterface.MouseKey getKey(MouseEvent e){
		return switch(e.getButton()){
			case BUTTON1 -> DisplayInterface.MouseKey.LEFT;
			case BUTTON2 -> DisplayInterface.MouseKey.RIGHT;
			default -> {
				throw new RuntimeException("Unknown event"+e);
			}
		};
	}
	
	public G2DBackend(Panel target){
		this.target=target;
		target.setFocusable(true);
		target.requestFocus();
		
		target.addMouseMotionListener(new MouseMotionAdapter(){
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
		target.addMouseListener(new MouseAdapter(){
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
				return target.getWidth();
			}
			@Override
			public int getHeight(){
				return target.getHeight();
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
				target.addComponentListener(new ComponentAdapter(){
					@Override
					public void componentResized(ComponentEvent e){
						listener.run();
					}
				});
			}
			
			@Override
			public void registerKeyboardButton(Consumer<KeyboardEvent> listener){
				target.addKeyListener(new KeyAdapter(){
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
				target.addMouseListener(new MouseAdapter(){
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
				target.addMouseListener(new MouseAdapter(){
					@Override
					public void mouseWheelMoved(MouseWheelEvent e){
						listener.accept(e.getY());
					}
				});
			}
			
			@Override
			public void registerMouseMove(Runnable listener){
				target.addMouseListener(new MouseAdapter(){
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
				throw NotImplementedException.infer();//TODO: implement .isOpen()
			}
			@Override
			public void requestClose(){
				throw NotImplementedException.infer();//TODO: implement .requestClose()
			}
			@Override
			public void pollEvents(){
				throw NotImplementedException.infer();//TODO: implement .pollEvents()
			}
			@Override
			public void destroy(){
				throw NotImplementedException.infer();//TODO: implement .destroy()
			}
		};
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
	public GLFont.Bounds getStringBounds(String str){
		currentGraphics.setFont(currentGraphics.getFont().deriveFont(getFontScale()/2));
		var rect=currentGraphics.getFontMetrics().getStringBounds(str, currentGraphics);
		return new GLFont.Bounds((float)(rect.getWidth()), (float)(rect.getHeight()));
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
	public void outlineString(Color color, String str, float x, float y){
		currentGraphics.setColor(color);
		setStrokeWidth(1);
		currentGraphics.setFont(currentGraphics.getFont().deriveFont(getFontScale()/2));
		var transform=new AffineTransform();
		transform.translate(x, y);
		Shape shape=new TextLayout(str, currentGraphics.getFont(), currentGraphics.getFontRenderContext()).getOutline(transform);
		currentGraphics.draw(shape);
	}
	@Override
	public void fillString(Color color, String str, float x, float y){
		currentGraphics.setColor(color);
		currentGraphics.setFont(currentGraphics.getFont().deriveFont(getFontScale()/2));
		currentGraphics.drawString(str, x, y);
	}
	@Override
	public boolean canFontDisplay(char c){
		return currentGraphics.getFont().canDisplay(c);
	}
	@Override
	public void preRender(){
		var image=render;
		int w    =getDisplay().getWidth();
		int h    =getDisplay().getHeight();
		if(image==null||image.getWidth()!=w||image.getHeight()!=h){
			image=target.getGraphicsConfiguration().createCompatibleImage(w, h, Transparency.TRANSLUCENT);
			render=image;
		}
		
		currentGraphics=image.createGraphics();
		currentGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		currentGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		currentGraphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
	}
	@Override
	public void postRender(){
		currentGraphics.dispose();
		currentGraphics=null;
	}
	@Override
	public void runLater(Runnable task){
		invokeLater(task);
	}
	
	public BufferedImage getRender(){
		return render;
	}
}
