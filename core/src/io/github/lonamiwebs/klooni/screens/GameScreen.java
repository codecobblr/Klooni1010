package io.github.lonamiwebs.klooni.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import io.github.lonamiwebs.klooni.Klooni;
import io.github.lonamiwebs.klooni.game.BaseScorer;
import io.github.lonamiwebs.klooni.game.Board;
import io.github.lonamiwebs.klooni.game.GameLayout;
import io.github.lonamiwebs.klooni.game.Piece;
import io.github.lonamiwebs.klooni.game.PieceHolder;
import io.github.lonamiwebs.klooni.game.Scorer;
import io.github.lonamiwebs.klooni.game.TimeScorer;
import io.github.lonamiwebs.klooni.serializer.BinSerializable;
import io.github.lonamiwebs.klooni.serializer.BinSerializer;

// Main game screen. Here the board, piece holder and score are shown
class GameScreen implements Screen, InputProcessor, BinSerializable {

    //region Members

    private final BaseScorer scorer;

    private final Board board;
    private final PieceHolder holder;

    private final SpriteBatch batch;
    private final Sound gameOverSound;

    private final PauseMenuStage pauseMenu;

    // TODO Perhaps make an abstract base class for the game screen and game modes
    // by implementing different "isGameOver" etc. logic instead using an integer?
    private final int gameMode;

    private boolean gameOverDone;

    //endregion

    //region Static members

    private final static int BOARD_SIZE = 10;
    private final static int HOLDER_PIECE_COUNT = 3;

    final static int GAME_MODE_SCORE = 0;
    final static int GAME_MODE_TIME = 1;

    private final static String SAVE_DAT_FILENAME = ".klooni.sav";

    //endregion

    //region Constructor

    // Load any previously saved file by default
    GameScreen(final Klooni game, final int gameMode) {
        this(game, gameMode, true);
    }

    GameScreen(final Klooni game, final int gameMode, final boolean loadSave) {
        batch = new SpriteBatch();
        this.gameMode = gameMode;

        final GameLayout layout = new GameLayout();
        switch (gameMode) {
            case GAME_MODE_SCORE:
                scorer = new Scorer(game, layout);
                break;
            case GAME_MODE_TIME:
                scorer = new TimeScorer(game, layout);
                break;
            default:
                throw new RuntimeException("Unknown game mode given: "+gameMode);
        }

        board = new Board(layout, BOARD_SIZE);
        holder = new PieceHolder(layout, HOLDER_PIECE_COUNT, board.cellSize);
        pauseMenu = new PauseMenuStage(layout, game, scorer, gameMode);

        gameOverSound = Gdx.audio.newSound(Gdx.files.internal("sound/game_over.mp3"));

        if (loadSave) {
            // The user might have a previous game. If this is the case, load it
            tryLoad();
        }
        else {
            // Ensure that there is no old save, we don't want to load it, thus delete it
            deleteSave();
        }
    }

    //endregion

    //region Private methods

    // If no piece can be put, then it is considered to be game over
    private boolean isGameOver() {
        for (Piece piece : holder.getAvailablePieces())
            if (board.canPutPiece(piece))
                return false;

        return true;
    }

    private void doGameOver() {
        if (!gameOverDone) {
            gameOverDone = true;

            holder.enabled = false;
            pauseMenu.show(true);
            if (Klooni.soundsEnabled())
                gameOverSound.play();

            // The user should not be able to return to the game if its game over
            deleteSave();
        }
    }

    //endregion

    //region Screen

    @Override
    public void show() {
        if (pauseMenu.isShown()) // Will happen if we go to the customize menu
            Gdx.input.setInputProcessor(pauseMenu);
        else
            Gdx.input.setInputProcessor(this);
    }

    private void showPauseMenu() {
        pauseMenu.show(false);
        save(); // Save the state, the user might leave the game
    }

    @Override
    public void render(float delta) {
        Klooni.theme.glClearBackground();
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        if (scorer.isGameOver() && !pauseMenu.isShown()) {
            doGameOver();
        }

        batch.begin();

        scorer.draw(batch);
        board.draw(batch);
        holder.update();
        holder.draw(batch);

        batch.end();

        if (pauseMenu.isShown() || pauseMenu.isHiding()) {
            pauseMenu.act(delta);
            pauseMenu.draw();
        }
    }

    @Override
    public void dispose() {
        pauseMenu.dispose();
    }

    //endregion

    //region Input

    @Override
    public boolean keyUp(int keycode) {
        if (keycode == Input.Keys.P || keycode == Input.Keys.BACK) // Pause
            showPauseMenu();

        return false;
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        return holder.pickPiece();
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        int area = holder.calculateHeldPieceArea();
        int action = holder.dropPiece(board);
        if (action == PieceHolder.NO_DROP)
            return false;

        if (action == PieceHolder.ON_BOARD_DROP) {
            scorer.addPieceScore(area);
            scorer.addBoardScore(board.clearComplete(), board.cellCount);

            // After the piece was put, check if it's game over
            if (isGameOver()) {
                doGameOver();
            }
        }
        return true;
    }

    //endregion

    //region Unused methods

    @Override
    public void resize(int width, int height) { }

    @Override
    public void pause() { }

    @Override
    public void resume() { }

    @Override
    public void hide() { }

    @Override
    public boolean keyDown(int keycode) {
        return false;
    }

    @Override
    public boolean keyTyped(char character) {
        return false;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        return false;
    }

    @Override
    public boolean mouseMoved(int screenX, int screenY) {
        return false;
    }

    @Override
    public boolean scrolled(int amount) {
        return false;
    }

    //endregion

    //region Saving and loading

    private void save() {
        // Only save if the game is not over
        if (gameOverDone)
            return;

        final FileHandle handle = Gdx.files.local(SAVE_DAT_FILENAME);
        try {
            BinSerializer.serialize(this, handle.write(false));
        } catch (IOException e) {
            // Should never happen but what else could be done if the game wasn't saved?
            e.printStackTrace();
        }
    }

    static void deleteSave() {
        final FileHandle handle = Gdx.files.local(SAVE_DAT_FILENAME);
        if (handle.exists())
            handle.delete();
    }

    private boolean tryLoad() {
        // Load will fail if the game modes differ, but that's okay
        final FileHandle handle = Gdx.files.local(SAVE_DAT_FILENAME);
        if (handle.exists()) {
            try {
                BinSerializer.deserialize(this, handle.read());
                return true;
            } catch (IOException ignored) { }
        }
        return false;
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        // gameMode, board, holder, scorer
        out.writeInt(gameMode);
        board.write(out);
        holder.write(out);
        scorer.write(out);
    }

    @Override
    public void read(DataInputStream in) throws IOException {
        int savedGameMode = in.readInt();
        if (savedGameMode != gameMode)
            throw new IOException("A different game mode was saved. Cannot load the save data.");

        board.read(in);
        holder.read(in);
        scorer.read(in);
    }

    //endregion
}
