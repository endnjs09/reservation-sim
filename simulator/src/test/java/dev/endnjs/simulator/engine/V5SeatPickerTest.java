package dev.endnjs.simulator.engine;

import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class V5SeatPickerTest {
    private final SeatPicker picker=new SeatPicker();
    private List<SeatPicker.Seat> seats(long... ids) { return java.util.Arrays.stream(ids).mapToObj(id -> new SeatPicker.Seat(id,"s"+id,"AVAILABLE")).toList(); }
    @ParameterizedTest @ValueSource(ints={2,3,4})
    void groupsStayInOneRowAndUseOnlyContiguousAvailableSeats(int count) {
        var grid=java.util.stream.LongStream.rangeClosed(1,30).mapToObj(id -> new SeatPicker.Seat(id,"s"+id,id==5 ? "HELD" : "AVAILABLE")).toList();
        var random=new SplittableRandom(42);int front=0;
        for(int i=0;i<30000;i++) {
            var group=picker.pickGroup(grid,10,count,true,random);assertThat(group).hasSize(count);
            assertThat(group).allMatch(s -> s.status().equals("AVAILABLE") && s.id()!=5);
            assertThat((group.getFirst().id()-1)/10).isEqualTo((group.getLast().id()-1)/10);
            for(int n=1;n<count;n++) assertThat(group.get(n).id()).isEqualTo(group.getFirst().id()+n);
            if(group.getFirst().id()<=10) front++;
        }
        assertThat(front/30000.0).isCloseTo(.8,within(.01));
    }
    @Test void adjacentPairsAreWeightedTowardCenterOfRow() {
        int[] starts=new int[9];var random=new SplittableRandom(42);
        var grid=java.util.stream.LongStream.rangeClosed(1,10).mapToObj(id -> new SeatPicker.Seat(id,"s"+id,"AVAILABLE")).toList();
        for(int i=0;i<100000;i++) starts[(int)picker.pickGroup(grid,10,2,true,random).getFirst().id()-1]++;
        double[] weights={1.5,2.5,3.5,4.5,5.5,4.5,3.5,2.5,1.5};
        for(int i=0;i<9;i++) assertThat(starts[i]/100000.0).isCloseTo(weights[i]/29.5,within(.007));
    }
    @Test void requiredAdjacencyRejectsScatteredSeatsButOptionalAdjacencyCanSpanRows() {
        var grid=seats(1,3,7);var random=new SplittableRandom(42);
        assertThat(picker.pickGroup(grid,5,3,true,random)).isEmpty();
        for(int i=0;i<100;i++) {
            var result=picker.pickGroup(grid,5,3,false,random);
            if(!result.isEmpty()) assertThat(result.stream().map(SeatPicker.Seat::id)).containsExactly(1L,3L,7L);
        }
        assertThat(picker.pickGroup(grid,5,4,false,random)).isEmpty();
    }
    @Test void optionalAdjacencySearchesForLaterContiguousRowsBeforeScattering() {
        var grid=seats(1,3,6,7);var random=new SplittableRandom(42);
        for(int i=0;i<100;i++) assertThat(picker.pickGroup(grid,5,2,false,random).stream().map(SeatPicker.Seat::id)).containsExactly(6L,7L);
        assertThat(picker.pickGroup(seats(5,6),5,2,true,random)).isEmpty(); // Row boundary is not adjacency.
    }
}
