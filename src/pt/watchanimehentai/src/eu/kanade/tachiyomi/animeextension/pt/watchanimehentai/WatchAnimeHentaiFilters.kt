package eu.kanade.tachiyomi.animeextension.pt.watchanimehentai

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

internal class GenreFilter(
    genres: Array<Pair<String, String>>,
) : AnimeFilter.Select<String>(
    "Gênero",
    genres.map { it.first }.toTypedArray(),
) {
    private val slugs = genres.map { it.second }
    fun selectedSlug(): String = slugs[state]
}

internal class LetterFilter(
    letters: Array<Pair<String, String>>,
) : AnimeFilter.Select<String>(
    "Letra inicial",
    letters.map { it.first }.toTypedArray(),
) {
    private val slugs = letters.map { it.second }
    fun selectedSlug(): String = slugs[state]
}

internal object WatchAnimeHentaiFilters {
    val GENRES: Array<Pair<String, String>> = arrayOf(
        "Todos" to "",
        "Abuso" to "abuso",
        "Abuso no Trem" to "abuso-no-trem",
        "Ação" to "acao",
        "Ahegao" to "ahegao",
        "Anal" to "anal",
        "Aventura" to "aventura",
        "Beijo Grego" to "beijo-grego",
        "Bondage" to "bondage",
        "Boquete" to "boquete",
        "Brinquedos Sexuais" to "brinquedos-sexuais",
        "Bukkake" to "bukkake",
        "Chantagem" to "chantagem",
        "Colegiais" to "colegiais",
        "Comédia" to "comedia",
        "Cosplay" to "cosplay",
        "Cream Pie" to "cream-pie",
        "Creampie" to "creampie",
        "Demônios" to "demonios",
        "Dona de Casa" to "dona-de-casa",
        "Dupla Penetração" to "dupla-penetracao",
        "Elfa" to "elfa",
        "Empregada" to "empregada",
        "Enfermeiras" to "enfermeiras",
        "Escravas" to "escravas",
        "Espanhola" to "espanhola",
        "Facial" to "facial",
        "Fantasia" to "fantasia",
        "Femdom" to "femdom",
        "Ficção Científica" to "ficcao-cientifica",
        "Footjob" to "footjob",
        "Freiras" to "freiras",
        "Futanari" to "futanari",
        "Gangbang" to "gangbang",
        "Garganta Profunda" to "garganta-profunda",
        "Garota Gato" to "garota-gato",
        "Garotas Mágicas" to "garotas-magicas",
        "Gozada Interna" to "gozada-interna",
        "Grávida" to "gravida",
        "Gyaru" to "gyaru",
        "Harém" to "harem",
        "Humilhação" to "humilhacao",
        "Incesto" to "incesto",
        "Inflação" to "inflacao",
        "Lactação" to "lactacao",
        "Lavagem Cerebral" to "lavagem-cerebral",
        "Maiô" to "maio",
        "Masturbação" to "masturbacao",
        "Médica" to "medica",
        "Médico" to "medico",
        "Megane" to "megane",
        "Meias" to "meias",
        "MILF" to "milf",
        "Monstros" to "monstros",
        "NTR" to "ntr",
        "Orgia" to "orgia",
        "Peitões" to "peitoes",
        "Peitos" to "peitos",
        "Pele Morena" to "pele-morena",
        "POV" to "pov",
        "Princesa" to "princesa",
        "Professora" to "professora",
        "Quebra Mental" to "quebra-mental",
        "Raio-X" to "raio-x",
        "Romance" to "romance",
        "Safada" to "safada",
        "Secretárias" to "secretarias",
        "Seios Grandes" to "seios-grandes",
        "Sem Censura" to "sem-censura",
        "Sexo a Três" to "sexo-a-tres",
        "Sexo em Público" to "sexo-em-publico",
        "Shimapan" to "shimapan",
        "Sobrenatural" to "sobrenatural",
        "Squirt" to "squirt",
        "Submissão" to "submissao",
        "Súcubo" to "sucubo",
        "Superpoderes" to "superpoderes",
        "Tentáculos" to "tentaculos",
        "Tortura" to "tortura",
        "Tsundere" to "tsundere",
        "Urina" to "urina",
        "Vampiro" to "vampiro",
        "Vanilla" to "vanilla",
        "Vida Escolar" to "vida-escolar",
        "Virgens" to "virgens",
        "Yaoi" to "yaoi",
        "Yuri" to "yuri",
    )

    val LETTERS: Array<Pair<String, String>> = arrayOf("Todas" to "") +
        ('A'..'Z').map { it.toString() to it.lowercase() }.toTypedArray()

    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        GenreFilter(GENRES),
        LetterFilter(LETTERS),
    )
}
