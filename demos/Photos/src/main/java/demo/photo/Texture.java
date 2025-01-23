package demo.photo;

import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import demo.photo.db.ThumbnailDB;

import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static com.lapissea.util.UtilL.async;

public abstract class Texture{
	
	public static class SingleFile extends Texture{
		
		private final File file;
		
		public SingleFile(ThumbnailDB db, File file){
			super(db);
			this.file = Objects.requireNonNull(file);
		}
		
		@Override
		public boolean match(String query){
			return TextUtil.containsIgnoreCase(file.getPath(), query);
		}
		
		@NotNull
		@Override
		public String getName(){
			return file.getName();
		}
		
		@Override
		public void open(){
			try{
				Desktop.getDesktop().open(file);
			}catch(IOException e){
				e.printStackTrace();
			}
		}
		
		@NotNull
		@Override
		public List<TextureType> types(){
			return List.of(TextureType.COLOR);
		}
		
		@NotNull
		@Override
		public List<File> files(){
			return List.of(file);
		}
		
		@Override
		protected List<ThumbnailRenderer> thumbnailRenderers(){
			return List.of(new ThumbnailRenderer.DirectFile(file));
		}
	}
	
	public static class MultiMap extends Texture{
		
		private final List<TextureType> types;
		private final List<File>        files;
		
		public MultiMap(ThumbnailDB db, List<TextureType> types, List<File> files){
			super(db);
			var sorted = IntStream.range(0, files.size())
			                      .boxed()
			                      .sorted(Comparator.comparing(types::get).thenComparing(files::get))
			                      .mapToInt(Integer::intValue)
			                      .toArray();
			
			if(IntStream.range(0, files.size()).allMatch(i -> sorted[i] == i)){
				this.types = List.copyOf(types);
				this.files = List.copyOf(files);
			}else{
				this.types = Arrays.stream(sorted).mapToObj(types::get).toList();
				this.files = Arrays.stream(sorted).mapToObj(files::get).toList();
			}
			
		}
		
		@Override
		public boolean match(String query){
			return files.stream().anyMatch(file -> TextUtil.containsIgnoreCase(file.getPath(), query));
		}
		
		private File getFil(){
			return files.get(IntStream.range(0, files.size()).filter(i -> types.get(i) == TextureType.COLOR).findAny().orElse(0));
		}
		
		private File getFileBy(TextureType type){
			return files.get(types.indexOf(type));
		}
		
		@Override
		protected List<ThumbnailRenderer> thumbnailRenderers(){
			var col     = types.contains(TextureType.COLOR);
			var mask    = types.contains(TextureType.MASK);
			var preview = types.contains(TextureType.PREVIEW);
			
			var res = new ArrayList<ThumbnailRenderer>();
			if(preview){
				res.add(new ThumbnailRenderer.DirectFile(getFileBy(TextureType.PREVIEW)));
			}else{
				if(col) res.add(new ThumbnailRenderer.DirectFile(getFileBy(TextureType.COLOR)));
				if(col && mask) res.add(new ThumbnailRenderer.MaskedColor(getFileBy(TextureType.COLOR), getFileBy(TextureType.MASK)));
			}
			
			return res;
		}
		
		@NotNull
		@Override
		public String getName(){
			return getFil().getName();
		}
		
		@NotNull
		@Override
		public List<File> files(){
			return files;
		}
		
		@NotNull
		@Override
		public List<TextureType> types(){
			return types;
		}
		
		@Override
		public void open(){
			try{
				Desktop.getDesktop().open(getFil().getParentFile());
			}catch(IOException e){
				e.printStackTrace();
			}
		}
	}
	
	
	public static final int MAX_THUMB_SIZE = 300;
	
	private static final ThreadPoolExecutor RENDERER_POOL;
	private static final ForkJoinPool       THUMBNAIL_POOL = (ForkJoinPool)Executors.newWorkStealingPool(2);
	
	static{
		int nThreads = Runtime.getRuntime().availableProcessors() + 2;
		RENDERER_POOL = new ThreadPoolExecutor(
			nThreads, nThreads,
			10, TimeUnit.SECONDS,
			new LinkedBlockingQueue<>(),
			Thread.ofPlatform().priority(Thread.MIN_PRIORITY).name("Texture reader - ", 0).factory()
		);
		RENDERER_POOL.allowCoreThreadTimeOut(true);
	}
	
	public static boolean highWork(){
		return RENDERER_POOL.getActiveCount() == RENDERER_POOL.getMaximumPoolSize();
	}
	
	public static boolean noWork(){
		return RENDERER_POOL.getActiveCount() == 0 && THUMBNAIL_POOL.getActiveThreadCount() == 0;
	}
	
	private final ThumbnailDB db;
	protected Texture(ThumbnailDB db){ this.db = db; }
	
	public abstract boolean match(String query);
	
	public void ensureFinal(){
		RENDERER_POOL.submit(() -> ThumbnailRenderer.ensureFinalThumbnail(db, getThumbnailRenderers()));
	}
	
	public void readThumbnail(Consumer<BufferedImage> updateThumb){
		
		var cached = ThumbnailRenderer.tryGetFast(getThumbnailRenderers());//avoid async if image is cached in memory
		if(cached != null){
			updateThumb.accept(cached);
			return;
		}
		
		Runnable doRender = () -> async(() -> {
			synchronized(this){
				ThumbnailRenderer.render(db, getThumbnailRenderers(), updateThumb);
			}
		}, RENDERER_POOL);
		
		if(!highWork()){//not working hard so 2 workers not necessary
			doRender.run();
			return;
		}
		
		THUMBNAIL_POOL.submit(() -> {
			var cac = ThumbnailRenderer.tryGetCached(db, renderers);
			if(cac != null){
				updateThumb.accept(cac);
				return;
			}
			doRender.run();
		});
		
	}
	
	private List<ThumbnailRenderer> renderers;
	
	public List<ThumbnailRenderer> getThumbnailRenderers(){
		if(renderers == null){
			renderers = List.copyOf(thumbnailRenderers());
		}
		return renderers;
	}
	
	@Override
	public boolean equals(Object o){
		if(this == o) return true;
		if(!(o instanceof Texture)) return false;
		return files().equals(((Texture)o).files());
	}
	
	@Override
	public int hashCode(){
		return files().hashCode();
	}
	
	protected abstract List<ThumbnailRenderer> thumbnailRenderers();
	
	@NotNull
	public abstract String getName();
	
	@NotNull
	public abstract List<File> files();
	
	@NotNull
	public abstract List<TextureType> types();
	
	public abstract void open();
	
	@Override
	public String toString(){
		return "Texture{" + getName() + '}';
	}
	
	public ThumbnailDB getDB(){ return db; }
}
