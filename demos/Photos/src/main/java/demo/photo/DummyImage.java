package demo.photo;

import com.lapissea.util.WeakValueHashMap;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

public final class DummyImage{
	
	private record Node(String msg, int width, int height){ }
	
	private static BufferedImage renderDummy(Node node){
		var image = new BufferedImage(node.width, node.height, BufferedImage.TYPE_INT_RGB);
		
		var g = image.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		
		int w, h;
		g.setFont(g.getFont().deriveFont(1F));
		
		while(true){
			var mets = g.getFontMetrics();
			h = mets.getHeight();
			w = mets.stringWidth(node.msg);
			if(h/2>image.getHeight() || w>image.getWidth()) break;
			
			g.setFont(g.getFont().deriveFont(g.getFont().getSize() + 1F));
		}
		g.setFont(g.getFont().deriveFont(g.getFont().getSize() - 1F));
		
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		
		g.setColor(Color.LIGHT_GRAY);
		g.setStroke(new BasicStroke(3));
		g.drawLine(0, 0, image.getWidth(), image.getHeight());
		g.drawLine(image.getWidth(), 0, 0, image.getHeight());
		
		g.setColor(Color.GRAY);
		g.drawString(node.msg, (image.getWidth() - w)/2, (image.getHeight())/2);
		g.dispose();
		
		return image;
	}
	
	private static final WeakValueHashMap<Node, BufferedImage> MEMORY_CACHE = new WeakValueHashMap<>();
	
	public static BufferedImage get(String msg, int width, int height){
		return MEMORY_CACHE.computeIfAbsent(new Node(msg, width, height), DummyImage::renderDummy);
	}
	
	public static BufferedImage getLoading(int width, int height){
		return get("Loading image...", width, height);
	}
	
	public static BufferedImage getError(int width, int height){
		return get("Failed to load!", width, height);
	}
	
}
