package com.lapissea.cfs.tools.render;

import com.lapissea.cfs.tools.GLFont;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Deque;
import java.util.LinkedList;

import static javax.swing.SwingUtilities.invokeLater;

public class G2DBackend extends RenderBackend{
	
	private BufferedImage render;
	private Graphics2D    currentGraphics;
	
	private final Deque<AffineTransform> transformStack=new LinkedList<>();
	
	private final Panel target;
	private       int   mouseX;
	private       int   mouseY;
	
	public G2DBackend(Panel target){
		this.target=target;
		
		target.addMouseMotionListener(new MouseMotionAdapter(){
			@Override
			public void mouseMoved(MouseEvent e){
				mouseX=e.getX();
				mouseY=e.getY();
			}
			
			@Override
			public void mouseDragged(MouseEvent e){
				mouseX=e.getX();
				mouseY=e.getY();
			}
		});
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
				xFrom*getPixelsPerByte(), yFrom*getPixelsPerByte(),
				xTo*getPixelsPerByte(), yTo*getPixelsPerByte()
			)
		);
	}
	
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
		currentGraphics.fillRect(0, 0, getWidth(), getHeight());
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
	public void outlineString(String str, float x, float y){
		setStrokeWidth(1);
		currentGraphics.setFont(currentGraphics.getFont().deriveFont(getFontScale()/2));
		var transform=new AffineTransform();
		transform.translate(x, y);
		Shape shape=new TextLayout(str, currentGraphics.getFont(), currentGraphics.getFontRenderContext()).getOutline(transform);
		currentGraphics.draw(shape);
	}
	@Override
	public void fillString(String str, float x, float y){
		currentGraphics.setFont(currentGraphics.getFont().deriveFont(getFontScale()/2));
		currentGraphics.drawString(str, x, y);
	}
	@Override
	public boolean canFontDisplay(char c){
		return currentGraphics.getFont().canDisplay(c);
	}
	@Override
	public void preRender(){
		BufferedImage image=render;
		
		if(image==null||image.getWidth()!=getWidth()||image.getHeight()!=getHeight()){
			image=target.getGraphicsConfiguration().createCompatibleImage(getWidth(), getHeight(), Transparency.TRANSLUCENT);
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
