package demo.photo.ui;

import demo.photo.ResizeType;
import demo.photo.Texture;
import demo.photo.TextureType;
import demo.photo.Textures;
import demo.photo.TypedTextureFile;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

import static javax.swing.SwingUtilities.invokeLater;

public class HomeUI{
	public  JPanel                root;
	public  JTextField            searchField;
	public  JButton               searchButton;
	public  JPanel                searchResults;
	public  JScrollPane           resultScroll;
	public  JButton               removeDuplicatesButton;
	public  JButton               dragNDropButton;
	private JSpinner              resizeNumWidth;
	private JComboBox<ResizeType> resizeType;
	private JSpinner              resizeNumHeight;
	private JPanel                textureFiles;
	private JButton               closeTexture;
	private JScrollPane           textureViewScroll;
	private JPanel                textureView;
	public  JButton               renderAllThumbnailsButton;
	
	public HomeUI(){
		renderAllThumbnailsButton.setVisible(false);
		
		searchResults.setLayout(new WrapLayout());
		setDuplicateCount(0);
		
		resizeType.removeAllItems();
		for(ResizeType value : ResizeType.values()){
			resizeType.addItem(value);
		}
		
		BiConsumer<ResizeType, JSpinner> setFormat = (format, spinnner) -> {
			
			spinnner.setModel(new SpinnerNumberModel(0D, 0D, Double.MAX_VALUE, format.stepSize));
			spinnner.setEditor(new JSpinner.NumberEditor(spinnner, format.format));
		};
		
		resizeType.addActionListener(t -> {
			
			ResizeType format = resizeType.getItemAt(resizeType.getSelectedIndex());
			setFormat.accept(format, resizeNumWidth);
			setFormat.accept(format, resizeNumHeight);
		});
		
		closeTexture.addActionListener(t -> setTextureView(null));
		setTextureView(null);
		
		textureViewScroll.getVerticalScrollBar().setUnitIncrement(50);
		textureFiles.setLayout(new WrapLayout());
		textureView.getParent().addComponentListener(new ComponentAdapter(){
			@Override
			public void componentResized(ComponentEvent e){
				updateViewWidth();
			}
		});
	}
	
	private void createUIComponents(){
	}
	
	private void updateViewWidth(){
		root.revalidate();
		
		var p = textureView.getParent();
		
		int w;
		int count = textureFiles.getComponentCount();
		if(count == 0) w = Texture.MAX_THUMB_SIZE + 20;
		else{
			w = textureFiles.getComponent(0).getWidth();
			if(w == 0){
				invokeLater(this::updateViewWidth);
				return;
			}
		}
		
		
		w += textureViewScroll.getVerticalScrollBar().getWidth() + 10;
		
		int targeted = p.getWidth()/2;
		
		var siz    = new Dimension(Math.max(1, Math.min(count, targeted/w))*w, p.getHeight());
		var change = !textureView.getPreferredSize().equals(siz);
		
		if(change){
			textureView.setPreferredSize(siz);
			textureView.revalidate();
			
			root.revalidate();
			root.repaint();
		}
		
		var needsWatching = change || Arrays.stream(textureFiles.getComponents()).anyMatch(c -> {
			var tumb = ((ThumbnailElement)((JComponent)c).getComponent(0));
			return tumb.getLastVisible() == ThumbnailElement.Visibility.VISIBLE && tumb.isDummy();
		});
		if(needsWatching){
			invokeLater(this::updateViewWidth);
		}
	}
	
	public void setDuplicateCount(int count){
		var any = count>0;
		removeDuplicatesButton.setVisible(any);
		removeDuplicatesButton.setEnabled(any);
		if(!any) return;
		removeDuplicatesButton.setText("Remove " + count + " duplicates");
	}
	
	private Texture lastTexture;
	
	public void setTextureView(Texture texture){
		try{
			textureFiles.removeAll();
			
			if(texture != null && texture.equals(lastTexture)){
				texture = null;
			}
			lastTexture = texture;
			
			if(texture == null){
				textureView.setVisible(false);
				return;
			}
			
			textureView.setVisible(true);
			
			var fils = texture.typedFiles();
			
			for(TypedTextureFile fil : fils){
				if(fil.type() == TextureType.PREVIEW) continue;
				
				var tex = Textures.make(texture.getDB(), List.of(fil));
				tex.readThumbnail(i -> { });
				
				var pan = new JPanel();
				pan.setBackground(new Color(0, 0, 0, 0));
				pan.setLayout(new BorderLayout());
				
				pan.add(new ThumbnailElementClickable(tex){
					@Override
					protected void onClick(){
						getTexture().open();
					}
				}, BorderLayout.WEST);
				
				var lab = new JLabel("Type: " + fil.type().toString());
				lab.setFont(lab.getFont().deriveFont(lab.getFont().getSize2D()*1.5F));
				pan.add(lab, BorderLayout.NORTH);
				
				textureFiles.add(pan);
			}
			
			resizeType.setSelectedItem(ResizeType.NONE);
			
			
		}finally{
			updateViewWidth();
			root.revalidate();
			root.repaint();
		}
	}
}
