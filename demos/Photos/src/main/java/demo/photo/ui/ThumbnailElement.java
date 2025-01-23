package demo.photo.ui;

import demo.photo.DummyImage;
import demo.photo.Texture;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.Supplier;

import static demo.photo.Texture.MAX_THUMB_SIZE;

public class ThumbnailElement extends JPanel{
	
	public enum Visibility{
		INVISIBLE,
		CLOSE,
		VISIBLE
	}
	
	private final Texture     texture;
	private final JImagePanel img;
	private       Visibility  lastVisible;
	private       boolean     dummy = true;
	
	public ThumbnailElement(Texture texture){
		this(texture, new JPanel());
	}
	
	protected ThumbnailElement(Texture texture, JComponent wraper){
		this.texture = texture;
		
		setLayout(new BorderLayout());
		
		wraper.setLayout(new BorderLayout());
		
		img = new JImagePanel(DummyImage.getLoading(MAX_THUMB_SIZE, MAX_THUMB_SIZE));
		img.setBorder(new LineBorder(Color.GRAY));
		
		wraper.add(img, BorderLayout.WEST);
		
		JPanel labs = new JPanel();
		labs.setLayout(new BoxLayout(labs, BoxLayout.Y_AXIS));
		
		for(File f : texture.files()){
			labs.add(lab(f.getName()));
		}
		
		labs.setBackground(new Color(0, 0, 0, 0));
		
		wraper.add(labs, BorderLayout.SOUTH);
		wraper.setBorder(new EmptyBorder(5, 5, 5, 5));
		
		add(wraper, BorderLayout.CENTER);
	}
	
	private JLabel lab(String name){
		return new JLabel(name);
	}
	
	private boolean isIntersect(Rectangle rect, Rectangle bounds){
		rect.x -= bounds.x;
		rect.y -= bounds.y;
		SwingUtilities.computeIntersection(0, 0, bounds.width, bounds.height, rect);
		
		return !rect.isEmpty();
	}
	
	private Visibility visibilityStatus(Supplier<Rectangle> visibleRectGet){
		
		var bounds = getBounds();
		if(isIntersect(visibleRectGet.get(), bounds)) return Visibility.VISIBLE;
		
		if(Texture.highWork()) return Visibility.INVISIBLE; //already working a lot
		
		var paddingFac = 1;//double screen size
		
		var padded = visibleRectGet.get();
		
		var w = padded.width*paddingFac;
		var h = padded.height*paddingFac;
		padded.width += w;
		padded.height += h;
		padded.x -= w/2;
		padded.y -= h/2;
		
		return isIntersect(padded, bounds)? Visibility.CLOSE : Visibility.INVISIBLE;
	}
	
	@Override
	public void paint(Graphics g){
		applyVisibility(Visibility.VISIBLE);
		super.paint(g);
	}
	
	public void updateVisibility(Supplier<Rectangle> visibleRectGet){
		applyVisibility(visibilityStatus(visibleRectGet));
	}
	
	private void imageLoaded(BufferedImage image){
		dummy = lastVisible != Visibility.VISIBLE || image == null;
		if(dummy) image = dummy();//reject loaded thumbnail if element no longer visible
		img.setImage(image);
	}
	
	public void applyVisibility(Visibility visibility){
		if(lastVisible == visibility) return;
		lastVisible = visibility;
		
		if(visibility == Visibility.INVISIBLE){
			imageLoaded(null);
			return;
		}
		
		texture.readThumbnail(this::imageLoaded);
	}
	
	private BufferedImage dummy(){
		var i = img.getImage();
		return DummyImage.getLoading(i.getWidth(), i.getHeight());
	}
	
	public Texture getTexture(){
		return texture;
	}
	
	public boolean isDummy(){
		return dummy;
	}
	
	public Visibility getLastVisible(){
		return lastVisible;
	}
	
	@Override
	public String toString(){
		return "Thumbnail{" +
		       "texture=" + texture +
		       '}';
	}
}
