package com.eventticket.support;

import com.eventticket.api.model.MapElement;
import com.eventticket.api.model.SeatMap;
import com.eventticket.api.model.SeatMapSeat;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Seat maps for tests, built the way the editor builds them: a generated block of rows
 * (requirements/002 criterion 3), then whatever the test needs on top.
 */
public final class SeatMaps {

    private SeatMaps() {}

    /** Rows labelled A1, A2 ... B1, B2, all in one Pricing Tier. */
    public static SeatMap block(String tierName, int rows, int seatsPerRow) {
        List<SeatMapSeat> seats = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            char rowLetter = (char) ('A' + row);
            for (int seat = 1; seat <= seatsPerRow; seat++) {
                seats.add(seat(rowLetter + String.valueOf(seat), seat, row + 1, tierName));
            }
        }
        return new SeatMap(seats, List.of(stage()));
    }

    public static SeatMapSeat seat(String label, double x, double y, String tierName) {
        return new SeatMapSeat(label, BigDecimal.valueOf(x), BigDecimal.valueOf(y), tierName);
    }

    public static MapElement stage() {
        var stage = new MapElement(MapElement.KindEnum.STAGE, BigDecimal.ZERO, BigDecimal.ZERO);
        stage.setLabel("Stage");
        stage.setWidth(BigDecimal.valueOf(10));
        stage.setHeight(BigDecimal.ONE);
        return stage;
    }

    public static SeatMap of(SeatMapSeat... seats) {
        return new SeatMap(List.of(seats), List.of());
    }
}
