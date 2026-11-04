package com.trainbooking.bookingseat.board.controller;

import com.trainbooking.bookingseat.board.model.BoardResponse;
import com.trainbooking.bookingseat.board.model.BoardTripDetail;
import com.trainbooking.bookingseat.board.service.BoardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The live trips board for admins (read-only). {@code /admin/**} needs the ADMIN role here as well as at the Gateway. */
@RestController
public class BoardController {

    private final BoardService board;

    public BoardController(BoardService board) {
        this.board = board;
    }

    /** {@code GET /admin/board/trips?from=2026-11-04&to=2026-11-11&state=UPCOMING,FULL}: all parameters optional (see {@link BoardService#board}). */
    @GetMapping("/admin/board/trips")
    public BoardResponse trips(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                               @RequestParam(required = false) String state) {
        return board.board(from, to, state);
    }

    /** {@code GET /admin/board/trips/{id}}: one trip with the state of every seat for the full journey (a booking reference only on taken seats). */
    @GetMapping("/admin/board/trips/{id}")
    public BoardTripDetail detail(@PathVariable long id) {
        return board.detail(id);
    }
}
