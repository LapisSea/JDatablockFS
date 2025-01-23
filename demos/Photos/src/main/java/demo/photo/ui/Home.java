package demo.photo.ui;

import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.UtilL;
import demo.photo.Options;
import demo.photo.Texture;
import demo.photo.TextureDB;
import demo.photo.ThumbnailRenderer;

import javax.swing.*;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static com.lapissea.util.UtilL.async;
import static javax.swing.SwingUtilities.invokeLater;

public class Home extends JFrame{
	
	private       HomeUI    ui;
	private final TextureDB textureDb = new TextureDB();
	
	{
		textureDb.duplicatesNotification = dups -> ui.setDuplicateCount(dups.stream().mapToInt(l -> l.size() - 1).sum());
	}
	
	public void start(){
		
		setTitle("Texture manager");
		
		addWindowListener(new WindowAdapter(){
			@Override
			public void windowClosing(WindowEvent e){
				setTitle("Closing...");
				System.exit(0);
			}
		});
		
		setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		
		ui = new HomeUI();
		initUI();
		setContentPane(ui.root);
		setSize(1000, 600);
		setLocationRelativeTo(null);
		setVisible(true);
		
		async(() -> {
			var oldTitle = getTitle();
			setTitle("Indexing...");
			textureDb.get();
			setTitle(oldTitle);
		});
	}
	
	
	private List<Texture> search(String query){
		if(query.isEmpty()) return textureDb.get();
		var result = textureDb.get();
		return Iters.from(result).filter(t -> t.match(query)).toList();
	}
	
	private String        searched;
	private List<Texture> searchResult = List.of();
	
	
	private class ScrollBind{
		public final JComponent comp;
		public final int        offset;
		
		ScrollBind(JComponent comp){
			this.comp = comp;
			offset = ui.resultScroll.getVerticalScrollBar().getValue() - comp.getLocation().y;
		}
	}
	
	private ScrollBind sb = null;
	private long       ignore;
	
	private static final long IGNORE_TIMEOUT = 500;
	
	private ThumbnailElement makeThumbnail(Texture texture){
		return new ThumbnailElementClickable(texture, true){
			@Override
			protected void onClick(){
				sb = new ScrollBind(this);
				ignore = System.currentTimeMillis();
				Home.this.ui.setTextureView(getTexture());
			}
		};
	}
	
	private void preRenderAll(){
		ui.renderAllThumbnailsButton.setEnabled(false);
		var oldText = ui.renderAllThumbnailsButton.getText();
		
		Consumer<String> progress = tex -> {
			ui.renderAllThumbnailsButton.setText(tex);
			ui.renderAllThumbnailsButton.revalidate();
			ui.renderAllThumbnailsButton.repaint();
		};
		Runnable onDone = () -> {
			ui.renderAllThumbnailsButton.setText(oldText);
			
			ThumbnailRenderer.RENDER_TRIGGER = false;
			ui.renderAllThumbnailsButton.setEnabled(true);
			ui.renderAllThumbnailsButton.setVisible(false);
		};
		boolean[] individual = {false};
		Options.run(this, "Options",
		            new Options.Bool("Pre render individual", () -> individual[0] = true));
		
		textureDb.preRenderAll(individual[0], progress, onDone, (t) -> {
			if(searched == null || searched.isEmpty()){
				return false;
			}
			return searchResult.contains(t);
		});
	}
	
	private ThumbnailElement getThumbnail(int i){
		return (ThumbnailElement)ui.searchResults.getComponent(i);
	}
	
	private void initUI(){
		
		ui.searchResults.setPreferredSize(new Dimension(0, 0));
		ui.resultScroll.getVerticalScrollBar().setUnitIncrement(50);
		
		var st = new Thread(() -> {
			
			Runnable updateVisible = () -> {
				Rectangle rect    = ui.searchResults.getVisibleRect();
				Rectangle rectCpy = new Rectangle();
				Supplier<Rectangle> getter = () -> {
					rectCpy.setRect(rect);
					return rectCpy;
				};
				
				for(int i = 0, j = ui.searchResults.getComponentCount(); i<j; i++){
					getThumbnail(i).updateVisibility(getter);
				}
				
			};
			
			class Recursive<T>{
				T t;
			}
			
			Runnable refreshScroll;
			{
				Recursive<Runnable> refreshScroll0 = new Recursive<>();
				refreshScroll0.t = () -> {
					ui.resultScroll.revalidate();
					
					var s = ui.searchResults.getLayout().preferredLayoutSize(ui.searchResults);
					s.width = Math.min(ui.resultScroll.getWidth(), s.width);
					ui.searchResults.setPreferredSize(s);
					
					ui.resultScroll.revalidate();
					ui.searchResults.revalidate();
					
					var bind = sb;
					
					if(bind != null){
						var val = bind.comp.getLocation().y + bind.offset;
						ignore = System.currentTimeMillis();
						ui.resultScroll.getVerticalScrollBar().setValue(val);
					}
					
					if(ui.resultScroll.getHorizontalScrollBar().isVisible() && ui.resultScroll.getWidth()/((float)Texture.MAX_THUMB_SIZE)>2){
						invokeLater(refreshScroll0.t);
					}else ui.resultScroll.repaint();
				};
				refreshScroll = refreshScroll0.t;
			}
			
			ui.resultScroll.addComponentListener(new ComponentAdapter(){
				@Override
				public void componentResized(ComponentEvent e){
					if(sb == null){
						sb = IntStream.range(0, ui.searchResults.getComponentCount())
						              .mapToObj(i -> (JComponent)ui.searchResults.getComponent(i))
						              .filter(c -> !c.getVisibleRect().isEmpty())
						              .findAny()
						              .map(ScrollBind::new)
						              .orElse(null);
					}
					refreshScroll.run();
				}
			});
			
			Supplier<String> getQuery = () -> ui.searchField.getText().trim();
			
			
			Recursive<Runnable> expandEnd0 = new Recursive<>();
			expandEnd0.t = () -> {
				
				if(searchResult.size()<=ui.searchResults.getComponentCount()){
					return;
				}
				
				JScrollBar scrollBar = ui.resultScroll.getVerticalScrollBar();
				if(scrollBar.getMaximum() - (scrollBar.getValue() + scrollBar.getModel().getExtent())>=Texture.MAX_THUMB_SIZE*5){
					return;
				}
				
				var columnCount = ui.searchResults.getComponentCount();
				var columns     = 1;
				if(columnCount == 0){
					columns = ui.resultScroll.getHeight()/Texture.MAX_THUMB_SIZE + 1;
				}
				var rows = ui.searchResults.getWidth()/Texture.MAX_THUMB_SIZE;
				for(int i = 0, j = Math.min(rows*columns, searchResult.size() - columnCount); i<j; i++){
					Texture t;
					try{
						t = searchResult.get(ui.searchResults.getComponentCount());
					}catch(IndexOutOfBoundsException e){ break; }
					ui.searchResults.add(makeThumbnail(t));
				}
				updateVisible.run();
				refreshScroll.run();
				invokeLater(expandEnd0.t);
			};
			Runnable expandEnd = expandEnd0.t;
			
			ui.resultScroll.getVerticalScrollBar().addAdjustmentListener(event -> {
				if(sb != null && ignore>0){
					if(System.currentTimeMillis() - ignore>IGNORE_TIMEOUT){
						sb = null;
						ignore = 0;
					}
				}
				expandEnd.run();
			});
			
			ui.renderAllThumbnailsButton.addActionListener(t -> preRenderAll());
			
			int backgroundLoader = 0;
			
			while(true){
				if(ThumbnailRenderer.RENDER_TRIGGER){
					
					if(ui.renderAllThumbnailsButton.isEnabled()){
						ui.renderAllThumbnailsButton.setVisible(true);
						if(Texture.noWork()){
							var t = textureDb.get();
							if(t.size()>backgroundLoader){
								t.get(backgroundLoader++).ensureFinal();
							}
						}
					}
				}
				
				expandEnd.run();
				updateVisible.run();
				
				String query = getQuery.get();
				
				if(searched != null && searched.equals(query)){
					UtilL.sleep(500);
					continue;
				}
				
				searched = query;
				searchResult = search(query);
				
				var rows    = ui.resultScroll.getHeight()/Texture.MAX_THUMB_SIZE + 1;
				var columns = ui.searchResults.getWidth()/Texture.MAX_THUMB_SIZE;
				
				rows *= 1.5F;
				var count = columns*rows;
				
				
				for(int i = 0; i<Math.min(searchResult.size(), ui.searchResults.getComponentCount()); i++){
					var thumb  = getThumbnail(i);
					var newTex = searchResult.get(i);
					
					if(!thumb.getTexture().equals(newTex)){
						ui.searchResults.remove(i);
						if(i<=count) ui.searchResults.add(makeThumbnail(newTex), i);
					}
				}
				
				int c;
				while((c = ui.searchResults.getComponentCount())>searchResult.size()){
					ui.searchResults.remove(c - 1);
				}
				refreshScroll.run();
				
				expandEnd.run();
			}
		}, "Main loop");
		st.start();
		
		Runnable search = () -> {
			if(st.getState() == Thread.State.TIMED_WAITING) st.interrupt();
		};
		ui.searchButton.addActionListener(e -> search.run());
		ui.searchField.addKeyListener(new KeyAdapter(){
			@Override
			public void keyPressed(KeyEvent e){
				invokeLater(search);
			}
		});
		
		ui.removeDuplicatesButton.addActionListener(e -> {
			ui.removeDuplicatesButton.setEnabled(false);
			
			async(() -> {
				List<Texture> texturesToDelete = new ArrayList<>();
				
				Options.run(this, "Confirm deletion",
				            textureDb.getDups()
				                     .stream()
				                     .map(t -> new Options.TextureCompare(t, texturesToDelete::addAll))
				                     .toArray(Options.Option[]::new));
				
				if(texturesToDelete.isEmpty()){
					ui.removeDuplicatesButton.setEnabled(true);
					return;
				}
				
				texturesToDelete.stream()
				                .flatMap(t -> t.files().stream())
				                .forEach(File::delete);
				searched = null;
			});
			
		});
	}
	
	
}
