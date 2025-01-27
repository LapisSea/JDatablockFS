package demo.photo;

import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.WeakValueHashMap;
import demo.photo.db.ThumbnailDB;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static com.lapissea.util.UtilL.Assert;
import static demo.photo.Texture.MAX_THUMB_SIZE;
import static java.awt.image.BufferedImage.TYPE_INT_ARGB;

public abstract class ThumbnailRenderer{
	private static final WeakValueHashMap<File, BufferedImage> MEMORY_CACHE       = new WeakValueHashMap<File, BufferedImage>().defineStayAlivePolicy(10);
	private static final ThreadLocal<Map<File, BufferedImage>> SESSION_FILE_CACHE = ThreadLocal.withInitial(HashMap::new);
	private static final Lock                                  READ_LOCK          = new ReentrantLock();
	
	private static BufferedImage readImageFile(File file) throws IOException{
		var cache = SESSION_FILE_CACHE.get();
		
		var cached = cache.get(file);
		if(cached != null) return cached;
		
		byte[] data;
		READ_LOCK.lock();
		try{
			data = Files.readAllBytes(file.toPath());
		}finally{
			READ_LOCK.unlock();
		}
		var img = ImageIO.read(new ByteArrayInputStream(data));
		if(img == null) throw new IOException();
		
		cache.put(file, img);
		return img;
	}
	
	private static BufferedImage limitSize(BufferedImage thumb){
		int siz = Math.max(thumb.getWidth(), thumb.getHeight());
		
		if(siz<=Texture.MAX_THUMB_SIZE) return thumb;
		
		return resize(thumb, ((float)Texture.MAX_THUMB_SIZE)/siz);
	}
	
	private static BufferedImage resize(BufferedImage thumb, float scale){
		return resize(thumb, (int)(thumb.getWidth()*scale), (int)(thumb.getHeight()*scale));
	}
	
	private static BufferedImage resize(BufferedImage img, int newW, int newH){
		Image      tmp = null;
		Graphics2D g   = null;
		try{
			tmp = img.getScaledInstance(newW, newH, Image.SCALE_SMOOTH);
			var dimg = new BufferedImage(newW, newH, img.getType() == 0? TYPE_INT_ARGB : img.getType());
			
			g = dimg.createGraphics();
			g.drawImage(tmp, 0, 0, null);
			g.dispose();
			
			return dimg;
		}finally{
			if(tmp != null) tmp.flush();
			if(g != null) g.dispose();
		}
	}
	
	public static class DirectFile extends ThumbnailRenderer{
		private final File color;
		
		public DirectFile(File color){
			this.color = color;
		}
		
		@Override
		protected String getIdPath(){
			var path = color.getPath();
			var ext  = UtilL.fileExtension(path);
			
			return path.substring(0, path.length() - ext.length() - 1) + "_" + this.getClass().getSimpleName() + "." + ext;
		}
		
		@Override
		protected BufferedImage renderThumb() throws IOException{
			return readImageFile(color);
		}
	}
	
	public static class MaskedColor extends ThumbnailRenderer{
		private final File color;
		private final File mask;
		
		public MaskedColor(File color, File mask){
			this.color = color;
			this.mask = mask;
		}
		
		@Override
		protected String getIdPath(){
			var path = color.getPath();
			var ext  = UtilL.fileExtension(path);
			
			return path.substring(0, path.length() - ext.length() - 1) + "_" + this.getClass().getSimpleName() + "." + ext;
		}
		
		@Override
		protected BufferedImage renderThumb() throws IOException{
			var color = readImageFile(this.color);
			var mask  = readImageFile(this.mask);
			return ImageUitls.combineColorAlpha(color, mask);
		}
		
	}
	
	protected abstract String getIdPath();
	
	protected abstract BufferedImage renderThumb() throws IOException;
	
	private File thumbnailFile;
	
	private File getThumbnailFile(){
		if(thumbnailFile == null) thumbnailFile = new File(getIdPath());
		return thumbnailFile;
	}
	
	private BufferedImage readJpeg(ThumbnailDB db, File file) throws IOException{
		return db.readThumbnail(file);
	}
	
	public BufferedImage render(ThumbnailDB db){
		BufferedImage raw;
		try{
			raw = renderThumb();
		}catch(IOException e){
			var err = DummyImage.getError(MAX_THUMB_SIZE, MAX_THUMB_SIZE);
			synchronized(MEMORY_CACHE){
				MEMORY_CACHE.put(getThumbnailFile(), err);
			}
			return err;
		}
		
		var thumb = limitSize(raw);
		
		var file = getThumbnailFile();
		synchronized(MEMORY_CACHE){
			MEMORY_CACHE.put(file, thumb);
		}
		try{
			db.putThumbnail(file, thumb);
		}catch(IOException e){
			LogUtil.println(file);
			e.printStackTrace();
		}
		
		LogUtil.println("Baked thumbnail:", file);
		return thumb;
	}
	
	public void clearCached(){
		var file = getThumbnailFile();
		synchronized(MEMORY_CACHE){
			MEMORY_CACHE.remove(file);
		}
	}
	
	public final BufferedImage tryGetCached(ThumbnailDB db){
		
		var memoryCache = tryGetFast();
		if(memoryCache != null) return memoryCache;
		
		
		var file = getThumbnailFile();
		
		try{
			BufferedImage fileCache = readJpeg(db, file);
			if(fileCache == null) return null;
			
			synchronized(MEMORY_CACHE){
				MEMORY_CACHE.put(file, fileCache);
			}
			return fileCache;
		}catch(IOException e){
			e.printStackTrace();
		}
		
		return null;
	}
	
	public boolean hasThumbnail(ThumbnailDB db){
		return db.iconExists(getThumbnailFile());
	}
	
	public final BufferedImage tryGetFast(){
		return MEMORY_CACHE.get(getThumbnailFile());
	}
	
	public static BufferedImage tryGetFast(List<ThumbnailRenderer> renderers){
		for(int i = renderers.size() - 1; i>=0; i--){
			var img = renderers.get(i).tryGetFast();
			if(img != null){
				return img;
			}
		}
		return null;
	}
	
	public static BufferedImage tryGetCached(ThumbnailDB db, List<ThumbnailRenderer> renderers){
		for(int i = renderers.size() - 1; i>=0; i--){
			var img = renderers.get(i).tryGetCached(db);
			if(img != null){
				return img;
			}
		}
		return null;
	}
	
	public static void renderThumbnail(ThumbnailDB db, List<ThumbnailRenderer> renderers, Consumer<BufferedImage> updateThumb){
		RENDER_TRIGGER = true;
		try{
			ThumbnailRenderer last = null;
			for(ThumbnailRenderer renderer : renderers){
				
				updateThumb.accept(renderer.render(db));
				
				if(last != null) last.clearCached();
				last = renderer;
			}
		}finally{
			SESSION_FILE_CACHE.remove();
			System.gc();
		}
	}
	
	public static boolean RENDER_TRIGGER;
	
	public static void ensureFinalThumbnail(ThumbnailDB db, List<ThumbnailRenderer> renderers){
		var finalTex = renderers.getLast();
		
		if(finalTex.hasThumbnail(db)) return;
		if(finalTex.tryGetCached(db) != null) return;
		
		
		try{
			finalTex.render(db);
		}finally{
			SESSION_FILE_CACHE.remove();
			System.gc();
		}
	}
	
	public static void render(ThumbnailDB db, List<ThumbnailRenderer> renderers, Consumer<BufferedImage> updateThumb){
		Assert(!renderers.isEmpty());
		
		var cached = tryGetCached(db, renderers);
		if(cached != null){
			updateThumb.accept(cached);
			return;
		}
		
		renderThumbnail(db, renderers, updateThumb);
	}
}
