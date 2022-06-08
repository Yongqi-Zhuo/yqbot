package top.saucecode

import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.runBlocking
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.register
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.unregister
import net.mamoe.mirai.console.command.CompositeCommand
import net.mamoe.mirai.console.command.MemberCommandSender
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.*

class Dictionary {
    private val wordList: List<String>
    private val commonList: List<String>
    init {
        val dictBufferedReader = BufferedReader(InputStreamReader(javaClass.getResourceAsStream("/dictionary.txt")))
        val wordListBuilder = mutableListOf<String>()
        dictBufferedReader.use {
            while (true) {
                val line = it.readLine() ?: break
                if (line.isNotEmpty()) {
                    wordListBuilder.add(line)
                }
            }
        }
        wordList = wordListBuilder
        val commonBufferedReader = BufferedReader(InputStreamReader(javaClass.getResourceAsStream("/common.txt")))
        val commonListBuilder = mutableListOf<String>()
        commonBufferedReader.use {
            while (true) {
                val line = it.readLine() ?: break
                if (line.isNotEmpty()) {
                    commonListBuilder.add(line)
                }
            }
        }
        commonList = commonListBuilder
    }
    fun query(word: String): Boolean {
        val index = wordList.binarySearch(word)
        return index >= 0
    }
    fun randomCommonWord(): String {
        return commonList.random()
    }
}

data class MultiplayerGuess(val guess: String, val id: String) {
    constructor(jsonObject: JSONObject) : this(
        jsonObject.getString("guess"),
        jsonObject.getString("id")
    )
}
fun JSONObject.toMultiplayerGuess(): MultiplayerGuess = MultiplayerGuess(this)

data class Guess(val word: String, val a: Int, val b: Int, val author: String)

class WordGuess(val onJoined: (WordGuess) -> Unit,
                val onNewGuessFromOthers: (WordGuess, Guess, Boolean) -> Unit,
                val onNewGameFromOthers: (WordGuess) -> Unit,
                val onChatFromOthers: (WordGuess, String, String) -> Unit) {
    private var joined: Boolean = false
    private var socket: Socket? = null
    var answer: String = ""
        private set
    var guesses: MutableList<Guess> = mutableListOf()
        private set
    var win: Boolean = false
        private set
    private val dictionary: Dictionary = Dictionary()
    private fun processIncomingGuess(mguess: MultiplayerGuess): Pair<Guess, Boolean> {
        val l = minOf(mguess.guess.length, answer.length)
        var a = 0
        var b = 0
        for (i in 0 until l) {
            if (mguess.guess[i] in answer) {
                a += 1
            }
            if (mguess.guess[i] == answer[i]) {
                b += 1
            }
        }
        return Pair(Guess(mguess.guess, a, b, mguess.id), b == answer.length)
    }
    fun joinGame() {
        if (socket != null) {
            socket!!.connect()
        } else {
            val options = IO.Options.builder()
                .setExtraHeaders(mapOf("origin" to listOf("https://saucecode.top")))
                .build()
            socket = IO.socket("wss://saucecode.top:3001", options)
            socket!!.on("init") { args ->
                val answerAndGuesses = args[0] as JSONObject
                // parse json object
                answer = answerAndGuesses["answer"] as String
                println(answer)
                val mguesses = answerAndGuesses["mguesses"] as JSONArray
                guesses.clear()
                for (i in 0 until mguesses.length()) {
                    val mguessObject = mguesses[i] as JSONObject
                    val mguess = mguessObject.toMultiplayerGuess()
                    val (guess, othersWin) = processIncomingGuess(mguess)
                    if (othersWin) {
                        win = true
                        // println("${mguess.id} won!")
                    }
                    guesses.add(guess)
                    // println("Guess: ${guess.word} ${guess.a} ${guess.b} made by ${guess.author}")
                }
                onJoined(this)
            }
            socket!!.on(Socket.EVENT_CONNECT) {
                joined = true
                socket!!.emit("name", "yqbot")
            }
            socket!!.on(Socket.EVENT_DISCONNECT) {
                joined = false
            }
            socket!!.on("mguess") { args ->
                val mguess = (args[0] as JSONObject).toMultiplayerGuess()
                val (guess, isCorrect) = processIncomingGuess(mguess)
                guesses.add(guess)
                if (isCorrect) {
                    win = true
                }
                onNewGuessFromOthers(this, guess, isCorrect)
            }
            socket!!.on("new") { args ->
                val newGame = args[0] as JSONObject
                transitionToNewGame(newGame["word"] as String)
                onNewGameFromOthers(this)
            }
            socket!!.on("chat") { args ->
                val chatMessage = args[0] as JSONObject
                onChatFromOthers(this, chatMessage["message"] as String, chatMessage["author"] as String)
            }
            socket!!.open()
        }
    }
    enum class Validity {
        VALID,
        INVALID_LENGTH,
        INVALID_CHARACTERS,
        INVALID_WORD,
        REPEATED_GUESS;

        override fun toString(): String {
            return when (this) {
                VALID -> "Valid"
                INVALID_LENGTH -> "Invalid length"
                INVALID_CHARACTERS -> "Invalid characters"
                INVALID_WORD -> "Invalid word"
                REPEATED_GUESS -> "Repeated guess"
            }
        }
    }
    private fun checkValidity(word: String): Validity {
        for (element in word) {
            if (!element.isLetter()) {
                return Validity.INVALID_CHARACTERS
            }
        }
        if (word.length != answer.length) {
            return Validity.INVALID_LENGTH
        }
        if (!dictionary.query(word)) {
            return Validity.INVALID_WORD
        }
        return Validity.VALID
    }
    fun makeGuess(guess: String, playerId: String): Triple<Validity, Boolean, Guess?> {
        if (!joined) {
            joinGame()
        }
        val lowercaseGuess = guess.lowercase(Locale.getDefault())
        val validity = checkValidity(lowercaseGuess)
        if (validity == Validity.VALID) {
            if (guesses.any { it.word == lowercaseGuess }) {
                return Triple(Validity.REPEATED_GUESS, false, null)
            }
            socket!!.emit("name", playerId)
            socket!!.emit("guess", JSONObject(mapOf("guess" to guess, "win" to (lowercaseGuess == answer))))
            socket!!.emit("name", "yqbot")
            val (newGuess, isCorrect) = processIncomingGuess(MultiplayerGuess(lowercaseGuess, playerId))
            guesses.add(newGuess)
            return Triple(validity, isCorrect, newGuess)
        }
        return Triple(validity, false, null)
    }
    private fun transitionToNewGame(newWord: String) {
        answer = newWord
        win = false
        guesses.clear()
    }
    fun newGame() {
        val newWord = dictionary.randomCommonWord()
        transitionToNewGame(newWord)
        socket!!.emit("new", JSONObject(mapOf("word" to newWord, "custom" to false)))
    }
    fun sendChatMessage(message: String, playerId: String) {
        socket!!.emit("name", playerId)
        socket!!.emit("chat", message)
        socket!!.emit("name", "yqbot")
    }
    fun leave() {
        socket?.disconnect()
        socket = null
        joined = false
    }
}

class WordGuessManagerPerGroup {
    private var game: WordGuess? = null
    private val helpHint = "Send \"/wordguess help\" for help.\n"
    private val commonHint = "Send \"/wordguess guess <word>\" to make a guess.\n$helpHint"
    private val winHint = "Send \"/wordguess new\" to start a new game.\n$helpHint"
    private val hint: String
        get() = if (game!!.win) "The answer is ${game!!.answer}\n$winHint" else "Count of letters: ${game!!.answer.length}\n$commonHint"
    var joined: Boolean = false
        private set
    val gameBoard: String
        get() = "----------------\n" + game!!.guesses.joinToString("\n") {
            "${it.word} ${it.a} ${it.b}"
        } + "\n----------------\n" + hint
    val win: Boolean
        get() = game!!.win
    fun joinGame(onJoined: (WordGuess) -> Unit,
                 onNewGuessFromOthers: (WordGuess, Guess, Boolean) -> Unit,
                 onNewGameFromOthers: (WordGuess) -> Unit,
                 onChatFromOthers: (WordGuess, String, String) -> Unit): Boolean {
        if (game == null) {
            game = WordGuess(onJoined, onNewGuessFromOthers, onNewGameFromOthers, onChatFromOthers)
            joined = true
            game!!.joinGame()
            return true
        }
        return false
    }
    fun makeGuess(guess: String, playerId: String) = game!!.makeGuess(guess, playerId)
    fun newGame() = game!!.newGame()
    fun sendChatMessage(message: String, playerId: String) = game!!.sendChatMessage(message, playerId)
    fun leave(): Boolean {
        if (game != null) {
            game!!.leave()
            game = null
            joined = false
            return true
        }
        return false
    }
}

object WordGuessManager {
    private val games = mutableMapOf<Long, WordGuessManagerPerGroup>()
    operator fun get(groupId: Long): WordGuessManagerPerGroup {
        return games.getOrPut(groupId) { WordGuessManagerPerGroup() }
    }
    fun load() {
        WordGuessCommand.register()
    }
    fun unload() {
        WordGuessCommand.unregister()
    }
}

object WordGuessCommand : CompositeCommand(
    Yqbot, "wordguess", "词猜",
    description = "WordGuess the game. 支持与web端的玩家一同游玩。"
) {
    @SubCommand
    suspend fun MemberCommandSender.help() {
        sendMessage(
            """这是WordGuess的yqbot桥接版。支持与 WordGuess Web 上的玩家一同游玩。
            |/wordguess help 获取帮助
            |/wordguess join 加入游戏
            |/wordguess guess <word> 猜单词
            |/wordguess new 新开一局游戏
            |/wordguess chat <message> 向web上的玩家发送聊天信息
            |/wordguess leave 退出游戏""".trimMargin())
    }
    @SubCommand
    suspend fun MemberCommandSender.join() {
        val manager = WordGuessManager[group.id]
        if (!manager.joinGame(
            onJoined = {
                runBlocking {
                    val msg = "Joined the game.\n" + manager.gameBoard
                    sendMessage(msg)
                }
            },
            onNewGuessFromOthers = { _, guess, isCorrect ->
                runBlocking {
                    val msg ="${guess.author} guessed ${guess.word}. ${guess.a}, ${guess.b}.\n" +
                            manager.gameBoard +
                            if (isCorrect) sendMessage("${guess.author} wins!\n") else ""
                    sendMessage(msg)
                }
            },
            onNewGameFromOthers = {
                runBlocking {
                    sendMessage("New game started.\n" + manager.gameBoard)
                }
            },
            onChatFromOthers = { _, message, author ->
                runBlocking {
                    sendMessage("[WordGuess chat] $author: $message")
                }
            }
        )) {
            sendMessage("You are already in the game.")
        }
    }
    @SubCommand
    suspend fun MemberCommandSender.guess(word: String) {
        val manager = WordGuessManager[group.id]
        if (!manager.joined) {
            sendMessage("You are not in the game. Send \"/wordguess join\" to join the game.")
            return
        }
        if (manager.win) {
            sendMessage("The game is over. Send \"/wordguess new\" to start a new game.")
            return
        }
        val (validity, isCorrect, guess) = manager.makeGuess(word, name)
        when (validity) {
            WordGuess.Validity.VALID -> {
                val msg ="${guess!!.author} guessed ${guess.word}. ${guess.a}, ${guess.b}.\n" +
                        manager.gameBoard +
                        if (isCorrect) "${guess.author} wins!\n" else ""
                sendMessage(msg)
            }
            WordGuess.Validity.INVALID_CHARACTERS -> sendMessage("Invalid characters.\n")
            WordGuess.Validity.INVALID_LENGTH -> sendMessage("Mismatched count of letters.\n")
            WordGuess.Validity.INVALID_WORD -> sendMessage("Wrong spelling.\n")
            WordGuess.Validity.REPEATED_GUESS -> sendMessage("Repeated guess.\n")
        }
    }
    @SubCommand
    suspend fun MemberCommandSender.new() {
        val manager = WordGuessManager[group.id]
        if (!manager.joined) {
            sendMessage("You are not in the game. Send \"/wordguess join\" to join the game.")
            return
        }
        manager.newGame()
        sendMessage("New game started.\n" + manager.gameBoard)
    }
    @SubCommand
    suspend fun MemberCommandSender.chat(message: String) {
        val manager = WordGuessManager[group.id]
        if (!manager.joined) {
            sendMessage("You are not in the game. Send \"/wordguess join\" to join the game.")
            return
        }
        manager.sendChatMessage(message, name)
        sendMessage("Message delivered.")
    }
    @SubCommand
    suspend fun MemberCommandSender.leave() {
        val manager = WordGuessManager[group.id]
        if (manager.leave()) {
            sendMessage("Left the game.")
        } else {
            sendMessage("You are not in the game.")
        }
    }
}