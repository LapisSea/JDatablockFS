package demo.photo.ui;

import javax.swing.*;
import java.awt.Dimension;
import java.awt.image.BufferedImage;

public class JImagePanel extends JLabel{
	
	private BufferedImage image;
	
	public JImagePanel(BufferedImage image){
		setImage(image);
	}
	
	public void setImage(BufferedImage image){
		if(this.image == image) return;
		this.image = image;
		setMaximumSize(new Dimension(image.getWidth(), image.getHeight()));
		setMinimumSize(new Dimension(image.getWidth(), image.getHeight()));
		setPreferredSize(new Dimension(image.getWidth(), image.getHeight()));
		setIcon(new ImageIcon(image));
		
		var root = getRootPane();
		if(root != null){
			root.revalidate();
			root.repaint();
		}
	}
	
	public BufferedImage getImage(){
		return image;
	}
}
