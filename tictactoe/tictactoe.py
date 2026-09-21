#!/usr/bin/env python3
"""Tic-Tac-Toe fuer zwei Spieler mit tkinter."""

import tkinter as tk
from tkinter import font

WIN_LINES = [
    (0, 1, 2), (3, 4, 5), (6, 7, 8),  # Reihen
    (0, 3, 6), (1, 4, 7), (2, 5, 8),  # Spalten
    (0, 4, 8), (2, 4, 6),             # Diagonalen
]


class TicTacToe:
    def __init__(self, root):
        self.root = root
        self.root.title("Tic-Tac-Toe")
        self.root.resizable(False, False)

        self.board = [" "] * 9
        self.player = "X"
        self.game_over = False

        self.big_font = font.Font(size=32, weight="bold")
        self.status_font = font.Font(size=14)

        self.status = tk.Label(root, text=f"Spieler {self.player} ist dran", font=self.status_font)
        self.status.grid(row=0, column=0, columnspan=3, pady=10)

        self.buttons = []
        for i in range(9):
            btn = tk.Button(
                root,
                text=" ",
                font=self.big_font,
                width=4,
                height=2,
                command=lambda i=i: self.make_move(i),
            )
            btn.grid(row=1 + i // 3, column=i % 3)
            self.buttons.append(btn)

        restart = tk.Button(root, text="Neustart", command=self.restart)
        restart.grid(row=4, column=0, columnspan=3, pady=10, sticky="we")

    def make_move(self, i):
        if self.game_over or self.board[i] != " ":
            return

        self.board[i] = self.player
        self.buttons[i].config(text=self.player)

        win_line = self.winning_line()
        if win_line:
            for i in win_line:
                self.buttons[i].config(fg="red")
            self.status.config(text=f"Spieler {self.player} gewinnt!")
            self.game_over = True
            return

        if " " not in self.board:
            self.status.config(text="Unentschieden!")
            self.game_over = True
            return

        self.player = "O" if self.player == "X" else "X"
        self.status.config(text=f"Spieler {self.player} ist dran")

    def winning_line(self):
        for a, b, c in WIN_LINES:
            if self.board[a] != " " and self.board[a] == self.board[b] == self.board[c]:
                return (a, b, c)
        return None

    def restart(self):
        self.board = [" "] * 9
        self.player = "X"
        self.game_over = False
        for btn in self.buttons:
            btn.config(text=" ", fg="black")
        self.status.config(text=f"Spieler {self.player} ist dran")


def main():
    root = tk.Tk()
    TicTacToe(root)
    root.mainloop()


if __name__ == "__main__":
    main()
