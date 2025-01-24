package demo.photo;

import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.dfs.utils.iterableplus.Match.Some;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import demo.photo.db.ThumbnailDB;

import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
		
		@Override
		public List<TypedTextureFile> typedFiles(){
			return List.of(new TypedTextureFile(TextureType.COLOR, file));
		}
		
		@Override
		protected List<ThumbnailRenderer> thumbnailRenderers(){
			return List.of(new ThumbnailRenderer.DirectFile(file));
		}
	}
	
	public static class MultiMap extends Texture{
		
		private final List<TypedTextureFile> files;
		
		public MultiMap(ThumbnailDB db, List<TypedTextureFile> files){
			super(db);
			this.files = Iters.from(files)
			                  .sorted(Comparator.comparing(TypedTextureFile::type)
			                                    .thenComparing(TypedTextureFile::file))
			                  .toList();
		}
		
		@Override
		public boolean match(String query){
			return Iters.from(files).anyMatch(f -> TextUtil.containsIgnoreCase(f.file().getPath(), query));
		}
		
		private File getFil(){
			return byType(TextureType.COLOR).orElse(files.getFirst().file());
		}
		private Match<File> byType(TextureType type){
			return Iters.from(files).firstMatchingM(t -> t.type() == type).map(TypedTextureFile::file);
		}
		
		@Override
		protected List<ThumbnailRenderer> thumbnailRenderers(){
			if(byType(TextureType.PREVIEW) instanceof Some(var preview)){
				return List.of(new ThumbnailRenderer.DirectFile(preview));
			}
			
			var res = new ArrayList<ThumbnailRenderer>();
			
			if(byType(TextureType.COLOR) instanceof Some(var col)){
				res.add(new ThumbnailRenderer.DirectFile(col));
				
				if(byType(TextureType.MASK) instanceof Some(var mask)){
					res.add(new ThumbnailRenderer.MaskedColor(col, mask));
				}
			}
			
			return res;
		}
		
		@NotNull
		@Override
		public String getName(){
			return getFil().getName();
		}
		@Override
		public List<TypedTextureFile> typedFiles(){
			return files;
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
	
	
	public static final int MAX_THUMB_SIZE = 256;
	
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
		return o instanceof Texture t && typedFiles().equals(t.typedFiles());
	}
	
	@Override
	public int hashCode(){
		return typedFiles().hashCode();
	}
	
	protected abstract List<ThumbnailRenderer> thumbnailRenderers();
	
	@NotNull
	public abstract String getName();
	
	@NotNull
	public abstract List<TypedTextureFile> typedFiles();
	
	@NotNull
	public final IterablePP<File> files(){
		return Iters.from(typedFiles()).map(TypedTextureFile::file);
	}
	
	public abstract void open();
	
	@Override
	public String toString(){
		return "Texture{" + getName() + '}';
	}
	
	public ThumbnailDB getDB(){ return db; }
}
