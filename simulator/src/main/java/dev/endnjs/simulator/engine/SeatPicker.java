package dev.endnjs.simulator.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.TreeMap;

public final class SeatPicker {
    public record Seat(long id, String label, String status) {}
    public Optional<Seat> pick(List<Seat> seats,int cols,SplittableRandom random) {
        return pickGroup(seats,cols,1,true,random).stream().findFirst();
    }
    public List<Seat> pickGroup(List<Seat> seats,int cols,int count,boolean adjacentRequired,SplittableRandom random) {
        if(cols<=0 || count<1) throw new IllegalArgumentException("Invalid seat grid/count");
        var rows=new TreeMap<Long,List<Seat>>();
        seats.stream().filter(s -> s.status().equals("AVAILABLE")).sorted(Comparator.comparingLong(Seat::id))
                .forEach(s -> rows.computeIfAbsent((s.id()-1)/cols,ignored -> new ArrayList<>()).add(s));
        if(rows.values().stream().mapToInt(List::size).sum()<count) return List.of();
        var ordered=new ArrayList<>(rows.values());
        int first=ordered.size()>1 && random.nextDouble()>=.8 ? 1 : 0;
        for(int row=first;row<ordered.size();row++) {
            var groups=new ArrayList<List<Seat>>(); var candidates=ordered.get(row);
            for(int from=0;from+count<=candidates.size();from++) {
                boolean contiguous=true;
                for(int i=1;i<count;i++) if(candidates.get(from+i).id()!=candidates.get(from).id()+i) { contiguous=false;break; }
                if(contiguous) groups.add(candidates.subList(from,from+count));
            }
            if(!groups.isEmpty()) return List.copyOf(weightedGroup(groups,cols,random));
        }
        if(adjacentRequired) return List.of();
        var chosen=new ArrayList<Seat>();
        for(int row=first;row<ordered.size() && chosen.size()<count;row++) {
            var remaining=new ArrayList<>(ordered.get(row));
            while(!remaining.isEmpty() && chosen.size()<count) {
                var singles=remaining.stream().map(List::of).toList();
                var seat=weightedGroup(singles,cols,random).getFirst();chosen.add(seat);remaining.remove(seat);
            }
        }
        return chosen.size()==count ? chosen.stream().sorted(Comparator.comparingLong(Seat::id)).toList() : List.of();
    }
    private static List<Seat> weightedGroup(List<List<Seat>> groups,int cols,SplittableRandom random) {
        double total=groups.stream().mapToDouble(g -> weight(g,cols)).sum();double draw=random.nextDouble(total);
        for(var group:groups) { draw-=weight(group,cols);if(draw<0) return group; }
        return groups.getLast();
    }
    private static double weight(List<Seat> group,int cols) {
        double center=((group.getFirst().id()-1)%cols+(group.getLast().id()-1)%cols)/2.0;
        return cols/2.0+.5-Math.abs(center-(cols-1)/2.0);
    }
}
