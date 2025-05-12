package demo.photo;

import com.lapissea.util.UtilL;
import demo.photo.ui.ThumbnailElement;
import demo.photo.ui.ThumbnailElementSelectable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Options{
	
	public interface Option{
		void initElements(JPanel panel);
		
		void exec();
	}
	
	public static class TextureCompare implements Option{
		final Consumer<List<Texture>>          selected;
		final List<Texture>                    textures;
		final List<ThumbnailElementSelectable> btns;
		
		public TextureCompare(List<Texture> textures, Consumer<List<Texture>> selected){
			this.selected = selected;
			this.textures = textures;
			
			this.btns = textures.stream()
			                    .skip(1)
			                    .map(t -> new ThumbnailElementSelectable(t, true))
			                    .collect(Collectors.toUnmodifiableList());
			
		}
		
		@Override
		public void initElements(JPanel panel){
			JPanel p = new JPanel();
			p.setLayout(new FlowLayout());
			p.add(new ThumbnailElement(textures.getFirst()));
			btns.forEach(p::add);
			panel.add(p);
		}
		
		@Override
		public void exec(){
			selected.accept(btns.stream()
			                    .filter(ThumbnailElementSelectable::isSelected)
			                    .map(ThumbnailElement::getTexture)
			                    .collect(Collectors.toUnmodifiableList()));
		}
	}
	
	public static class Bool implements Option{
		final Runnable  ifTrue;
		final JCheckBox box;
		
		public Bool(String text, Runnable ifTrue){
			this.box = new JCheckBox();
			box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
			var off = box.getPreferredSize().width;
			for(String s : text.split("\n")){
				var l = new JLabel(s);
				l.setBorder(BorderFactory.createEmptyBorder(0, off, 0, 0));
				box.add(l);
			}
			this.ifTrue = ifTrue;
		}
		
		@Override
		public void initElements(JPanel panel){
			panel.add(box);
		}
		
		@Override
		public void exec(){
			if(box.isSelected()) ifTrue.run();
		}
	}
	
	public static class Int implements Option{
		final IntConsumer run;
		final JSpinner    spinner;
		final String      text;
		
		public Int(String text, IntConsumer run){
			this.text = text;
			this.spinner = new JSpinner(new SpinnerNumberModel(5, 1, 10, 1));
			this.run = run;
		}
		
		@Override
		public void initElements(JPanel panel){
			JPanel p = new JPanel();
			p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
			
			var l = new JLabel(text);
			l.setBorder(new EmptyBorder(0, 0, 0, 5));
			
			p.add(l);
			p.add(spinner);
			
			panel.add(p);
		}
		
		@Override
		public void exec(){
			run.accept((Integer)spinner.getValue());
		}
	}
	
	public static class Resolution implements Option{
		final IntConsumer        run;
		final JComboBox<Integer> box;
		final String             text;
		
		private static final String NONE = "Nothing";
		
		@SuppressWarnings("unchecked")
		public Resolution(String text, int[] resolutions, IntConsumer run){
			this.text = text;
			this.box = new JComboBox(Stream.concat(Stream.of(NONE), IntStream.of(resolutions).mapToObj(r -> r + "K")).toArray());
			this.run = run;
		}
		
		@Override
		public void initElements(JPanel panel){
			JPanel p = new JPanel();
			p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
			
			var l = new JLabel(text);
			l.setBorder(new EmptyBorder(0, 0, 0, 5));
			
			p.add(l);
			p.add(box);
			
			panel.add(p);
		}
		
		@Override
		public void exec(){
			var res = (String)box.getSelectedItem();
			if(res == null || res.equals(NONE)) return;
			
			run.accept(Integer.parseInt(res.substring(0, res.length() - 1)));
		}
	}
	
	public static class Sepperator implements Option{
		@Override
		public void initElements(JPanel panel){
			JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
			Dimension  d         = separator.getPreferredSize();
			d.height = 1;
			separator.setPreferredSize(d);
			panel.setLayout(new FlowLayout(FlowLayout.CENTER, 0, 0));
			panel.setBackground(Color.LIGHT_GRAY);
			panel.add(separator);
		}
		
		@Override
		public void exec(){
		}
	}
	
	public static void run(JFrame parent, String title, Option... options){
		
		boolean[] clicked = {false};
		
		var frame = new JDialog(parent, title, true);
		
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		
		JPanel p = new JPanel();
		p.setBorder(new EmptyBorder(30, 0, 10, 0));
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		
		JPanel opts = new JPanel();
		opts.setBorder(new EmptyBorder(10, 30, 10, 30));
		opts.setLayout(new BoxLayout(opts, BoxLayout.Y_AXIS));
		opts.setAlignmentX(Component.RIGHT_ALIGNMENT);
		
		for(Option option : options){
			JPanel opp = new JPanel();
			opp.setLayout(new FlowLayout(FlowLayout.CENTER, 0, 3));
			option.initElements(opp);
			opts.add(opp);
		}
		
		JButton dun = new JButton("Done");
		dun.addActionListener(e -> {
			clicked[0] = true;
			frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
		});
		
		JPanel optWrapp = new JPanel(new FlowLayout());
		optWrapp.add(opts);
		
		var scroll = new JScrollPane(optWrapp);
		scroll.getVerticalScrollBar().setUnitIncrement(50);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		p.add(scroll);
		
		JPanel dunP = new JPanel();
		p.setBorder(new EmptyBorder(10, 0, 30, 0));
		dunP.add(dun);
		p.add(dunP);
		
		frame.setContentPane(p);
		
		frame.pack();
		
		var s  = frame.getSize();
		var ps = parent.getSize();
		
		s.width = Math.min(s.width + 20, ps.width - 10);
		s.height = Math.min(s.height + 20, ps.height - 10);
		
		frame.setSize(s);
		
		frame.setLocationRelativeTo(parent);
		frame.setVisible(true);
		
		UtilL.sleepWhile(frame::isDisplayable, 10);
		
		if(clicked[0]){
			for(Option option : options){
				option.exec();
			}
		}
	}
	
	
}
