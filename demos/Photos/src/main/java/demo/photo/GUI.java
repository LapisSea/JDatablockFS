package demo.photo;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;

public class GUI extends JFrame{
	
	static{
		try{
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		}catch(Exception ignore){ }
	}
	
	public Consumer<String> searchChange;
	public Consumer<File>   fileDrop;
	
	private final JPanel imagePanel;
	private final JLabel totalCountLabel;
	
	public GUI(){
		super("Photos");
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setLayout(new BorderLayout());
		
		// Enable drag-and-drop for the frame
		new DropTarget(this, new DropTargetListener(){
			@Override
			public void dragEnter(DropTargetDragEvent dtde){ }
			
			@Override
			public void dragOver(DropTargetDragEvent dtde){ }
			
			@Override
			public void dropActionChanged(DropTargetDragEvent dtde){ }
			
			@Override
			public void dragExit(DropTargetEvent dte){ }
			
			@Override
			public void drop(DropTargetDropEvent dtde){
				try{
					dtde.acceptDrop(DnDConstants.ACTION_COPY);
					Transferable transferable = dtde.getTransferable();
					if(transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)){
						//noinspection unchecked
						List<File> files = (List<File>)transferable.getTransferData(DataFlavor.javaFileListFlavor);
						for(File file : files){
							fileDrop.accept(file);
						}
					}
				}catch(Exception e){
					e.printStackTrace();
				}
			}
		});
		
		
		var searchBar = new JTextField();
		searchBar.setFont(searchBar.getFont().deriveFont(18F));
		searchBar.addActionListener(ignore -> searchChange.accept(searchBar.getText()));
		searchBar.addKeyListener(new KeyAdapter(){
			@Override
			public void keyReleased(KeyEvent e){
				searchChange.accept(searchBar.getText());
			}
		});
		
		totalCountLabel = new JLabel("Total images: 0");
		totalCountLabel.setFont(new Font("Arial", Font.PLAIN, 14));
		
		var layout = new BorderLayout();
		layout.setVgap(10);
		JPanel searchPanel = new JPanel(layout);
		searchPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		searchPanel.add(totalCountLabel, BorderLayout.NORTH);
		searchPanel.add(searchBar, BorderLayout.CENTER);
		add(searchPanel, BorderLayout.NORTH);
		
		// Create the panel for displaying images
		imagePanel = new JPanel();
		imagePanel.setLayout(new GridLayout(0, 3, 10, 10)); // Adjust grid layout as needed
		JScrollPane scrollPane = new JScrollPane(imagePanel);
		scrollPane.getVerticalScrollBar().setUnitIncrement(50);
		add(scrollPane, BorderLayout.CENTER);
		
		// Set the frame size and make it visible
		setSize(1000, 600);
		setLocationRelativeTo(null);
		setVisible(true);
	}
	
	public record NamedImage(String name, BufferedImage image){ }
	
	public void updateImages(List<NamedImage> images){
		SwingUtilities.invokeLater(() -> {
			imagePanel.removeAll();
			
			for(var image : images){
				
				JPanel panel = new JPanel();
				panel.setLayout(new BorderLayout());
				
				JLabel imageLabel = new JLabel(new ImageIcon(image.image));
				JLabel nameLabel  = new JLabel(image.name, SwingConstants.CENTER);
				
				panel.add(imageLabel, BorderLayout.CENTER);
				panel.add(nameLabel, BorderLayout.SOUTH);
				
				imagePanel.add(panel);
			}
			
			imagePanel.revalidate();
			imagePanel.repaint();
		});
	}
	
	public void setTotalCount(long count){
		totalCountLabel.setText("Total images: " + count);
	}
}
