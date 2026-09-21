"""Tic-Tac-Toe fuer zwei Spieler in der Konsole."""

WIN_LINES = [
    (0, 1, 2), (3, 4, 5), (6, 7, 8),  # Reihen
    (0, 3, 6), (1, 4, 7), (2, 5, 8),  # Spalten
    (0, 4, 8), (2, 4, 6),             # Diagonalen
]


def print_board(board):
    rows = [" | ".join(board[i:i + 3]) for i in (0, 3, 6)]
    print("\n---------\n".join(rows))


def winner(board):
    for a, b, c in WIN_LINES:
        if board[a] != " " and board[a] == board[b] == board[c]:
            return board[a]
    return None


def get_move(board, player):
    while True:
        raw = input(f"Spieler {player}, waehle ein Feld (1-9): ")
        if not raw.isdigit() or not 1 <= int(raw) <= 9:
            print("Bitte eine Zahl von 1 bis 9 eingeben.")
            continue
        pos = int(raw) - 1
        if board[pos] != " ":
            print("Feld ist bereits belegt.")
            continue
        return pos


def main():
    board = [" "] * 9
    player = "X"

    while True:
        print_board(board)
        board[get_move(board, player)] = player

        win = winner(board)
        if win:
            print_board(board)
            print(f"Spieler {win} gewinnt!")
            break
        if " " not in board:
            print_board(board)
            print("Unentschieden!")
            break

        player = "O" if player == "X" else "X"


if __name__ == "__main__":
    main()
