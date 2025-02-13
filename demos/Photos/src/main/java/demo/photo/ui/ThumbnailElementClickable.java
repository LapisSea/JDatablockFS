package demo.photo.ui;

import com.lapissea.util.LogUtil;
import com.lapissea.util.NotNull;
import demo.photo.Texture;

import javax.swing.*;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceAdapter;
import java.awt.dnd.DragSourceDropEvent;
import java.io.File;
import java.util.List;

public abstract class ThumbnailElementClickable extends ThumbnailElement{
	
	protected final JButton button;
	
	public ThumbnailElementClickable(Texture texture){
		this(texture, false);
	}
	
	public ThumbnailElementClickable(Texture texture, boolean draggable){
		super(texture, new JButton(){
			@Override
			protected void paintComponent(Graphics g){
				g.setColor(this.getBackground());
				super.paintComponent(g);
				var g2 = (Graphics2D)g;
				
				var comp = g2.getComposite();

//				g2.setComposite(MultiplyComposite.MULTIPLY);
				
				g2.fillRect(0, 0, getWidth(), getHeight());
				
				g2.setComposite(comp);
			}
		});
		
		button = (JButton)this.getComponent(0);
		button.setCursor(new Cursor(Cursor.HAND_CURSOR));
		
		button.addActionListener(e -> onClick());
		
		if(draggable){
			
			DragSource ds = new DragSource();
			Transferable transferable = new Transferable(){
				
				@Override
				public DataFlavor[] getTransferDataFlavors(){
					return new DataFlavor[]{DataFlavor.javaFileListFlavor};
				}
				
				@Override
				public boolean isDataFlavorSupported(DataFlavor flavor){
					return DataFlavor.javaFileListFlavor.equals(flavor);
				}
				
				@NotNull
				@Override
				public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException{
					if(!flavor.isFlavorJavaFileListType()) throw new UnsupportedFlavorException(flavor);
//				    if(file[0]==null) file[0]=File.createTempFile(this.hashCode()+"", "mark");
//				    return Collections.singletonList(file[0]);
					return texture.files().toModList();
				}
			};
			
			ds.createDefaultDragGestureRecognizer(button, DnDConstants.ACTION_COPY, dge -> {
				ds.startDrag(dge, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR), transferable, new DragSourceAdapter(){
					@Override
					public void dragDropEnd(DragSourceDropEvent dsde){
						if(!dsde.getDropSuccess()) return;
						
						JFrame f = new JFrame("fucc");
						f.setLocation(dsde.getLocation());
						f.setSize(100, 100);
						f.setVisible(true);
						
						setCursor(Cursor.getDefaultCursor());
						try{
							@SuppressWarnings("unchecked") java.util.List<File> dropppedFiles = (List<File>)transferable.getTransferData(DataFlavor.javaFileListFlavor);
							LogUtil.println("it's done!");
							LogUtil.println(dropppedFiles);
						}catch(Exception e){
							e.printStackTrace();
						}
					}
					
				});
			});
			
		}
	}
	
	protected abstract void onClick();
	
}
