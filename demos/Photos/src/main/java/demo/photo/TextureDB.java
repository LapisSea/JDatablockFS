package demo.photo;

import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.WeakValueHashMap;
import com.lapissea.util.function.FunctionOI;
import demo.photo.db.ThumbnailDB;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.lapissea.util.UtilL.async;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

public class TextureDB{
	
	private record Entry(List<File> files, ThumbnailDB db){ }
	
	private static final Map<Entry, Texture> OBJ_CACHE = new WeakValueHashMap<Entry, Texture>().defineStayAlivePolicy(1);
	
	public static Texture make(ThumbnailDB db, List<TextureType> maps, List<File> files){
		return OBJ_CACHE.computeIfAbsent(new Entry(files, db), fs -> {
			if(files.size() == 1) return new Texture.SingleFile(db, files.getFirst());
			else return new Texture.MultiMap(db, maps, files);
		});
	}
	
	private final ThumbnailDB thumbnailDB = new ThumbnailDB();
	
	private List<Texture>                 cache;
	public  Consumer<List<List<Texture>>> duplicatesNotification;
	private List<List<Texture>>           dupsLast;
	
	public List<List<Texture>> getDups(){
		return dupsLast;
	}
	
	private ThumbnailDB getThumbnailDB(){
		return thumbnailDB;
	}
	
	public synchronized List<Texture> get(){
		if(cache != null) return cache;
		
		List<Texture> textures = new ArrayList<>();
		
		iterFiles(new File("."), texture -> {
			synchronized(textures){
				textures.add(texture);
			}
		});
		
		textures.sort((a, b) -> ListComparator.compareLists(a.files(), b.files()));
		
		var duplicates =
			textures.stream()
			        .collect(groupingBy(Texture::getName))
			        .values()
			        .stream()
			        .filter(l -> l.size()>1)
			        .map(l -> {
				        Function<Texture, long[]> getSiz = t -> t.files().stream().mapToLong(File::length).toArray();
				        
				        var first = l.getFirst();
				        var exra  = getSiz.apply(first);
				        return l.stream()
				                .filter(t -> first == t || Arrays.equals(exra, getSiz.apply(t)))
				                .toList();
			        })
			        .filter(l -> l.size()>1)
			        .toList();
		
		if(!duplicates.isEmpty()){
			for(List<Texture> l : duplicates){
				textures.removeAll(l);
			}
		}
		
		
		if(!duplicates.equals(dupsLast)){
			dupsLast = duplicates;
			duplicatesNotification.accept(dupsLast);
		}
		
		textures.sort(Comparator.comparingInt((Texture t) -> -t.files().size()).thenComparing(Texture::getName));
		
		
		cache = textures;
		return cache;
	}
	
	private final Map<String, Boolean> extensionValidity = new ConcurrentHashMap<>();
	
	private void iterFiles(File folder, Consumer<Texture> addTexture){
		var fs = folder.listFiles();
		if(fs == null) return;
		
		LogUtil.println(folder);
		
		var files          = new ArrayList<File>(fs.length);
		var subfolderTasks = new ArrayList<CompletableFuture<Void>>(fs.length);
		
		for(File file : fs){
			if(file.isDirectory()){
				subfolderTasks.add(async(() -> iterFiles(file, addTexture), Thread.ofVirtual()::start));
			}else{
				var ext = UtilL.fileExtension(file.getPath()).toLowerCase();
				if(isExtensionValid(ext) && file.length()>0){
					files.add(file.toPath().normalize().toFile());
				}
			}
		}
		try{
			if(files.isEmpty()) return;
			if(files.size() == 1){
				texturePerFile(files, addTexture);
				return;
			}
			
			filesToTexture(files, addTexture);
		}finally{
			for(var task : subfolderTasks){
				task.join();
			}
		}
	}
	private boolean isExtensionValid(String ext){
		if(ext.isEmpty()) return false;
		return extensionValidity.computeIfAbsent(ext, k -> ImageIO.getImageReadersBySuffix(k).hasNext());
	}
	
	private void filesToTexture(List<File> files, Consumer<Texture> addTexture){
		if(files.isEmpty()) return;
		
		if(files.size() == 1){
			texturePerFile(files, addTexture);
			return;
		}
		
		List<String> paths = Iters.from(files).toList(f -> {
			var path = f.getPath();
			var name = f.getName();
			var pos  = name.lastIndexOf('.');
			if(pos != -1){
				var off = name.length() - pos;
				return path.substring(0, path.length() - off);
			}
			return path;
		});
		
		
		int startOffset = calcStartOffset(paths);
		
		if(startOffset == 0){
			texturePerFile(files, addTexture);
			return;
		}
		
		var endOffset = calcEndOffset(paths);
		
		var names = Iters.from(paths).toModList(p -> {
			int end = p.length() - endOffset;
			if(startOffset>=end) return "";
			return p.substring(startOffset, p.length() - endOffset).toUpperCase();
		});
		
		
		var   clumpIndexes = new int[names.size()];
		int[] counter      = {0};
		var   nums         = new HashMap<String, Integer>();
		
		//compute pre-map type marker differences
		{
			FunctionOI<String> getIndex = num -> {
				if(num.equals("_")) num = "";
				//noinspection AutoBoxing
				return nums.computeIfAbsent(num, n -> counter[0]++);
			};
			
			StringBuilder num = new StringBuilder();
			
			for(int i = 0; i<names.size(); i++){
				var name = names.get(i);
				
				num.setLength(0);
				char c;
				while(name.length()>num.length()){
					c = name.charAt(num.length());
					if(!"_0123456789 ".contains(Character.toString(c))){
						if(num.length() == 0) break;//needs smth before
						if(!Character.isDigit(num.charAt(num.length() - 1))) break;//last has to be number
						if(Character.toUpperCase(c) != 'K') break;//allow number+K
						int off = 0;
						while(num.length()>(off++) && Character.isDigit(num.charAt(num.length() - off))){
							num.setCharAt(num.length() - off, '0');//zero out resolution characters
						}
					}
					num.append(c);
				}
				
				var numStr = num.toString();
				clumpIndexes[i] = getIndex.apply(numStr);
				names.set(i, names.get(i).substring(num.length()));
				
			}
		}
		
		//split by pre maker differences
		if(counter[0]>1){
			
			if(nums.size() == names.size()){
				texturePerFile(files, addTexture);
				return;
			}
			
			for(int j = 0; j<counter[0]; j++){
				var clumpId = j;
				
				filesToTexture(IntStream.range(0, clumpIndexes.length)
				                        .filter(i -> clumpIndexes[i] == clumpId)
				                        .mapToObj(files::get)
				                        .collect(Collectors.toList()), addTexture);
			}
			return;
		}
		
		
		List<TextureType> maps = detectMapTypes(names);
		
		//all ok
		if(!maps.contains(null)){
			multiTexture(maps, files, addTexture);
			return;
		}
		
		var success     = new ArrayList<File>(maps.size());
		var successMaps = new ArrayList<TextureType>(maps.size());
		
		for(int i : IntStream.range(0, maps.size()).map(i -> maps.size() - i - 1).filter(i -> maps.get(i) != null).toArray()){
			success.add(files.remove(i));
			successMaps.add(maps.remove(i));
			names.remove(i);
		}
		//rescue ok
		if(!success.isEmpty()){
			multiTexture(successMaps, success, addTexture);
		}
		
		//split by name differences
		{
			Collection<List<Integer>> groups;
			
			int count = 0;
			var all   = IntStream.range(0, names.size()).boxed().toArray(Integer[]::new);
			var fail  = "\0";
			
			while(true){
				var len = ++count;
				var res = Arrays.stream(all)
				                .collect(groupingBy(((Integer i) -> {
					                var name = names.get(i);
					                return name.length()<=len? fail : name.substring(0, len);
				                })));
				
				if(res.size() == 1 || len>=startOffset){
					if(res.keySet().iterator().next().equals(fail)){
						groups = null;
						break;
					}
				}
				groups = res.values();
				if(groups.size()>1) break;
			}
			
			if(groups != null){
				groups.stream()
				      .map(indices -> indices.stream()
				                             .map(files::get)
				                             .collect(toList()))
				      .forEach(fils -> filesToTexture(fils, addTexture));
				
				return;
			}
		}
		
		//end of the road, noting more to group
		if(success.isEmpty()){
			texturePerFile(files, addTexture);
			return;
		}
		
		//retry failed
		filesToTexture(files, addTexture);
		
	}
	
	private void multiTexture(List<TextureType> maps, List<File> files, Consumer<Texture> addTexture){
		if(maps.stream().allMatch(m -> m == maps.getFirst())){
			texturePerFile(files, addTexture);
		}else{
			addTexture.accept(make(getThumbnailDB(), maps, files));
		}
	}
	
	private void texturePerFile(List<File> files, Consumer<Texture> addTexture){
		for(File file : files){
			addTexture.accept(make(getThumbnailDB(), null, List.of(file)));
		}
	}
	
	private int calcEndOffset(List<String> paths){
		
		var path1 = paths.getFirst();
		
		int endOffset = 1;
		outer:
		for(; endOffset<path1.length(); endOffset++){
			char c = path1.charAt(path1.length() - endOffset);
			
			for(int i = 1; i<paths.size(); i++){
				var path = paths.get(i);
				var pos  = path.length() - endOffset;
				if(path.length()<=pos || path.charAt(pos) != c) break outer;
			}
		}
		
		return endOffset - 1;
	}
	
	private int calcStartOffset(List<String> paths){
		
		var path1 = paths.getFirst();
		
		int startOffset = 0;
		outer:
		for(; startOffset<path1.length(); startOffset++){
			char c = path1.charAt(startOffset);
			
			for(int i = 1; i<paths.size(); i++){
				var path = paths.get(i);
				if(path.length()<=startOffset || path.charAt(startOffset) != c) break outer;
			}
		}
		return startOffset;
	}
	
	
	private List<TextureType> detectMapTypes(List<String> names){
		List<TextureType> types = new ArrayList<>(names.size());
		
		for(String name : names){
			types.add(detectMapType(name));
		}
		
		return types;
	}
	
	private TextureType detectMapType(String name){
		for(TextureType type : TextureType.values()){
			if(type.check.test(name)) return type;
		}
		return null;
	}
	
	private static void shuffleArray(int[] ar){
		var rnd = new RawRandom();
		for(int i = ar.length - 1; i>0; i--){
			int index = rnd.nextInt(i + 1);
			
			int a = ar[index];
			ar[index] = ar[i];
			ar[i] = a;
		}
	}
	
	@SuppressWarnings("AutoBoxing")
	public void preRenderAll(boolean individual, Consumer<String> progress, Runnable onDone, Predicate<Texture> hasPriority){
		async(() -> {
			var textures = get();
			var ij       = textures.size();
			
			progress.accept("Starting...");
			
			var indexArrP = IntStream.range(0, ij).toArray();
			shuffleArray(indexArrP);
			var indexArr = Arrays.stream(indexArrP).boxed().toList();
			
			var index = new ArrayList<Integer>(indexArrP.length);
			
			Consumer<BiConsumer<Texture, String>> doTexture = processor -> {
				
				index.clear();
				index.addAll(indexArr);
				int count = 0;
				
				while(!index.isEmpty()){
					int idPos = index.size() - 1;
					var opt   = IntStream.range(0, index.size()).filter(iPos -> hasPriority.test(textures.get(index.get(iPos)))).findAny();
					if(opt.isPresent()) idPos = opt.getAsInt();
					
					count++;
					
					int id = index.remove(idPos);
					processor.accept(textures.get(id), (opt.isPresent()? "Priority work" : "") + String.format("%.2f%%", (count/(float)ij)*100));
				}
			};
			
			doTexture.accept((texture, prog) -> {
				texture.ensureFinal();
				
				UtilL.sleepWhile(Texture::highWork);
				progress.accept(prog);
			});
			
			if(individual){
				progress.accept("Finishing part 1...");
				UtilL.sleepWhile(() -> !Texture.noWork(), 50);
				
				doTexture.accept((texture, prog) -> {
					for(File file : texture.files()){
						new Texture.SingleFile(getThumbnailDB(), file).ensureFinal();
						UtilL.sleepWhile(Texture::highWork, 5);
					}
					
					progress.accept(prog);
				});
			}
			
			progress.accept("Finishing...");
			UtilL.sleepWhile(() -> !Texture.noWork(), 50);
			
			onDone.run();
		});
	}
}
