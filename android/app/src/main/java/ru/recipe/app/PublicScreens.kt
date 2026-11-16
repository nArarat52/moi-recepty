package ru.recipe.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable fun CatalogScreen(vm:AppState) {
    ContentList {
        if(vm.route.kind=="catalog") {
            item {
                var search by rememberSaveable(vm.query) { mutableStateOf(vm.query) }
                Field(search,"Поиск по названию",{search=it})
                LaunchedEffect(search) { kotlinx.coroutines.delay(350);if(vm.query!=search){vm.query=search;vm.refresh()} }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    (listOf("Все")+Categories).forEach { c->FilterChip(selected=(if(c=="Все")"" else c)==vm.category,
                        onClick={vm.category=if(c=="Все")"" else c;vm.refresh()},label={Text(c)}) }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf(0,15,30,60).forEach { n->FilterChip(selected=vm.maxMinutes==n,onClick={vm.maxMinutes=n;vm.refresh()},label={Text(if(n==0)"Любое время" else "До $n мин")}) }
                }
                if(vm.query.isNotEmpty()||vm.category.isNotEmpty()||vm.maxMinutes>0)TextButton(onClick={vm.resetFilters()}){Text("Сбросить фильтры")}
            }
            item { PrimaryButton("+ Опубликовать рецепт",!vm.busy) { vm.requireLogin { vm.openForm() } } }
        }
        if(vm.entries.isEmpty()&&!vm.loading&&vm.error==null)item {
            EmptyState(when(vm.route.kind){"favorites"->"В избранном пока нет рецептов";"mine"->"У вас пока нет публикаций";else->"Рецепты не найдены. Измените запрос или фильтры"})
        }
        items(vm.entries,key={it.getLong("id")}) { r->
            InfoCard(r.text("title"),r.optInt("minutes").toString()+" мин · "+r.text("category")+" · "+ratingText(r),"Автор: "+r.text("author_name"),onClick={vm.go(Route("recipe",r.getLong("id")))}) {
                if(r.text("image_id").isNotEmpty())RemotePhoto(vm,r.text("image_id"))
            }
        }
        if(vm.hasMore)item { OutlinedButton(onClick={vm.page++;vm.reload(true)},enabled=!vm.loading){Text("Загрузить ещё")} }
    }
}
@Composable fun RecipeBody(vm:AppState,r:JSONObject) {
    Text(r.optInt("minutes").toString()+" минут · "+r.optInt("servings")+" порции · "+r.text("category"),color=Green,fontSize=14.sp)
    if(r.text("description").isNotEmpty())Text(r.text("description"),fontSize=14.sp,lineHeight=21.sp)
    if(r.text("image_id").isNotEmpty())RemotePhoto(vm,r.text("image_id"))
    InfoCard("Ингредиенты") {
        r.objects("ingredients").forEach { v->
            val n=v.optDouble("amount");val amount=if(n%1==0.0)n.toInt().toString() else n.toString().replace('.',',')
            Text(v.text("name")+" — "+amount+" "+v.text("unit"),fontSize=14.sp,lineHeight=22.sp)
        }
    }
    InfoCard("Приготовление") { r.strings("steps").forEachIndexed { i,s->Text((i+1).toString()+". "+s,fontSize=14.sp,lineHeight=22.sp) } }
}
@Composable fun RecipeScreen(vm:AppState) {
    val r=vm.data ?: return
    ContentList {
        item {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text(ratingText(r)+" · "+r.optInt("rating_count")+" оценок",fontSize=13.sp,color=Muted)
                IconButton(onClick={vm.requireLogin { vm.task { vm.api.call("/recipes/"+r.getLong("id")+"/favorite",if(r.optBoolean("favorite"))"DELETE" else "PUT");vm.refresh() } }},enabled=!vm.busy) {
                    Icon(if(r.optBoolean("favorite"))Icons.Default.Favorite else Icons.Default.FavoriteBorder,"Сохранить в избранное",tint=Green)
                }
            }
            Text("Автор: "+r.text("author_name"),fontSize=13.sp,color=Muted)
        }
        item { Column(verticalArrangement=Arrangement.spacedBy(14.dp)) { RecipeBody(vm,r) } }
        item { PrimaryButton("Оценки и отзывы",!vm.busy) { vm.go(Route("reviews",r.getLong("id"))) } }
        if(vm.user?.optLong("id")==r.optLong("author_id"))item { OutlinedButton(onClick={vm.openForm(r)},enabled=!vm.busy){Text("Редактировать рецепт")} }
    }
}
@Composable fun AuthScreen(vm:AppState,register:Boolean) {
    var login by rememberSaveable(register) { mutableStateOf("") }
    var name by rememberSaveable(register) { mutableStateOf("") }
    var password by remember(register) { mutableStateOf("") }
    var fields by remember { mutableStateOf<Map<String,String>>(emptyMap()) }
    val errors=fields+(vm.error?.fields ?: emptyMap())
    LaunchedEffect(login,name,password) { vm.transientDirty=login.isNotEmpty()||name.isNotEmpty()||password.isNotEmpty() }
    ContentList {
        item { Text(if(register)"Создайте свою кулинарную книгу" else "Готовьте и делитесь своими идеями",color=Muted,fontSize=14.sp) }
        item { Field(login,"Логин",{login=it},errors["login"],!vm.busy) }
        if(register)item { Field(name,"Имя",{name=it},errors["name"],!vm.busy) }
        item { Field(password,"Пароль",{password=it},errors["password"],!vm.busy,password=true) }
        item {
            PrimaryButton(if(register)"Зарегистрироваться" else "Войти",!vm.busy) {
                val f=mutableMapOf<String,String>()
                if(register&&!Regex("^[a-zA-Z0-9_]{3,30}$").matches(login.trim()))f["login"]="3–30 латинских букв, цифр или _"
                if(!register&&login.isBlank())f["login"]="Введите логин"
                if(register&&name.trim().length !in 2..50)f["name"]="Имя: от 2 до 50 символов"
                if(register&&password.length !in 8..64)f["password"]="Пароль: от 8 до 64 символов"
                if(!register&&password.isEmpty())f["password"]="Введите пароль"
                fields=f
                if(f.isEmpty())vm.task {
                    val body=json("login" to login.trim(),"password" to password)
                    if(register)body.put("name",name.trim())
                    val result=JSONObject(vm.api.call(if(register)"/auth/register" else "/auth/login","POST",body))
                    password="";vm.authenticated(result)
                }
            }
        }
        item { TextButton(onClick={vm.go(Route(if(register)"login" else "register"))},enabled=!vm.busy){Text(if(register)"У меня уже есть аккаунт" else "Создать аккаунт")} }
        item { TextButton(onClick={vm.go(Route("catalog"),true)},enabled=!vm.busy){Text("Продолжить как гость")} }
        item { TextButton(onClick={vm.go(Route("connection"))},enabled=!vm.busy){Text("Настроить подключение")} }
    }
}
@Composable fun ProfileScreen(vm:AppState) {
    val u=vm.user
    ContentList {
        if(u==null) {
            item { EmptyState("Готовьте вместе","Войдите, чтобы публиковать рецепты, сохранять избранное и работать в группах.") }
            item { PrimaryButton("Войти",!vm.busy){vm.go(Route("login"))} }
            item { OutlinedButton(onClick={vm.go(Route("register"))}){Text("Создать аккаунт")} }
        } else {
            item { InfoCard(u.text("name"),"@"+u.text("login")) }
            item { PrimaryButton("Мои публикации",!vm.busy){vm.resetFilters();vm.go(Route("mine"))} }
            item { OutlinedButton(onClick={vm.go(Route("invitations"))},enabled=!vm.busy){Text("Приглашения")} }
            item { OutlinedButton(onClick={vm.go(Route("subscription"))},enabled=!vm.busy){Text("Подписка")} }
            item {
                var confirm by remember { mutableStateOf(false) }
                TextButton(onClick={confirm=true},enabled=!vm.busy){Text("Выйти из аккаунта")}
                if(confirm)ConfirmDialog("Выйти из аккаунта?","Приватные данные будут очищены с устройства.",{confirm=false;vm.logout()},{confirm=false},"Выйти")
            }
        }
        item { TextButton(onClick={vm.go(Route("connection"))},enabled=!vm.busy){Text("Подключение к серверу")} }
        item { Text("Мои рецепты · версия 1.0.0",fontSize=12.sp,color=Muted) }
    }
}
@Composable fun ConnectionScreen(vm:AppState) {
    var url by rememberSaveable { mutableStateOf(vm.store.baseUrl) }
    var localError by remember { mutableStateOf<String?>(null) }
    ContentList {
        item { Text("Адрес сервера вашей учебной среды. Для эмулятора: http://10.0.2.2:8000",fontSize=14.sp,color=Muted) }
        item { Field(url,"Адрес сервера",{url=it},localError,!vm.busy) }
        item { PrimaryButton("Проверить и сохранить",!vm.busy) {
            val parsed=runCatching { java.net.URI(url.trim()) }.getOrNull()
            if(parsed?.scheme !in listOf("http","https")||parsed?.host.isNullOrBlank())localError="Введите адрес http:// или https://"
            else {
                localError=null
                val old=vm.store.baseUrl
                if(old!=url.trim().trimEnd('/')&&vm.user!=null)localError="Сначала выйдите из аккаунта, чтобы сменить сервер"
                else vm.task {
                    vm.store.baseUrl=url.trim()
                    try { vm.api.call("/health");vm.notice="Подключение работает" }
                    catch(e: Exception){vm.store.baseUrl=old;throw e}
                }
            }
        } }
    }
}
