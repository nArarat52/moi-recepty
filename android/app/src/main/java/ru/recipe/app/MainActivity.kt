package ru.recipe.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val Green=Color(0xFF356B4A)
val Ink=Color(0xFF25342B)
val Muted=Color(0xFF647268)
val Backdrop=Color(0xFFF7F9F7)
val Soft=Color(0xFFE4EFE6)
val Categories=listOf("Завтраки","Супы","Основные блюда","Салаты","Десерты","Напитки")
val Units=listOf("г","кг","мл","л","шт.","ч. л.","ст. л.")

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme=lightColorScheme(primary=Green,onPrimary=Color.White,secondary=Green,
                secondaryContainer=Soft,onSecondaryContainer=Ink,background=Backdrop,surface=Backdrop,
                onSurface=Ink,onBackground=Ink,surfaceVariant=Soft,onSurfaceVariant=Muted,outline=Color(0xFFD9E1DB))) { RecipeApp() }
        }
    }
}
fun dateText(raw: String): String = runCatching {
    OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm",Locale("ru")))
}.getOrDefault(raw)
fun ratingText(o: JSONObject): String = if(o.isNull("rating")) "Нет оценок" else "★ "+String.format(Locale("ru"),"%.1f",o.optDouble("rating"))

@Composable fun RecipeApp(vm: AppState=viewModel()) {
    val route=vm.route
    val snackbar=remember { SnackbarHostState() }
    var cancelForm by remember { mutableStateOf(false) }
    val backAction: ()->Unit = { if(!vm.busy) { if((route.kind=="form"&&vm.draft?.dirty==true)||vm.transientDirty)cancelForm=true else vm.back() } }
    BackHandler(enabled=vm.stack.size>1||vm.busy) { backAction() }
    LaunchedEffect(route,vm.requestRevision) { vm.reload() }
    LaunchedEffect(vm.notice) { vm.notice?.let { snackbar.showSnackbar(it);vm.notice=null } }
    val top=route.kind in listOf("catalog","favorites","groups","profile")
    val title=when(route.kind) {
        "catalog"->"Мои рецепты";"favorites"->"Избранное";"mine"->"Мои публикации"
        "recipe"->vm.data?.text("title") ?: "Рецепт";"reviews"->"Оценки и отзывы"
        "groups"->"Мои группы";"group"->vm.data?.text("title") ?: "Группа";"members"->"Участники"
        "dish"->"История блюда";"attempt"->"Попытка №"+(vm.data?.optInt("number") ?: "")
        "form"->if((vm.draft?.dishId ?: 0)>0)"Новая попытка" else if((vm.draft?.recipeId ?: 0)>0)"Редактирование" else "Новый рецепт"
        "login"->"Вход";"register"->"Регистрация";"profile"->"Профиль"
        "subscription"->"Подписка";"invitations"->"Приглашения";"connection"->"Подключение";else->"Мои рецепты"
    }
    Scaffold(containerColor=Backdrop,snackbarHost={ SnackbarHost(snackbar) },topBar={
        Column(Modifier.statusBarsPadding().fillMaxWidth().padding(start=24.dp,end=24.dp,top=12.dp,bottom=8.dp)) {
            if(!top) TextButton(onClick=backAction,enabled=!vm.busy,contentPadding=PaddingValues(0.dp)) {
                Icon(Icons.Default.ArrowBack,"Назад",Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text(if(route.kind=="form")"Отмена" else "Назад")
            }
            Text(title,fontSize=26.sp,fontWeight=FontWeight.Bold,lineHeight=34.sp)
            if(route.kind=="catalog") { Spacer(Modifier.height(8.dp));Text("Находите новое. Готовьте вместе.",color=Muted,fontSize=13.sp) }
            if(route.kind=="groups") { Spacer(Modifier.height(8.dp));Text("Закрытое пространство для команды",color=Muted,fontSize=13.sp) }
            if(vm.loading||vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=12.dp),color=Green)
        }
    },bottomBar={
        if(top||route.kind in listOf("recipe","group","mine")) NavigationBar(containerColor=Soft) {
            listOf(Triple("catalog","Каталог",Icons.Default.Menu),Triple("favorites","Избранное",Icons.Default.Favorite),
                Triple("groups","Группы",Icons.Default.Groups),Triple("profile","Профиль",Icons.Default.Person)).forEach { (kind,label,icon)->
                NavigationBarItem(selected=route.kind==kind,enabled=!vm.busy,
                    onClick={ if(kind in listOf("favorites","groups"))vm.requireLogin { vm.go(Route(kind),true) } else vm.go(Route(kind),true) },
                    icon={Icon(icon,label)},label={Text(label,fontSize=11.sp,maxLines=1)})
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            vm.error?.let { e->
                Surface(color=MaterialTheme.colorScheme.errorContainer,shape=RoundedCornerShape(12.dp),modifier=Modifier.padding(horizontal=24.dp,vertical=8.dp).fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(e.message,color=MaterialTheme.colorScheme.onErrorContainer,fontSize=14.sp)
                        if(e.status==0||e.status>=500)TextButton(onClick={vm.retry()},enabled=!vm.busy) { Text("Повторить") }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                when(route.kind) {
                    "catalog","favorites","mine"->CatalogScreen(vm)
                    "recipe"->RecipeScreen(vm)
                    "reviews"->ReviewsScreen(vm)
                    "groups"->GroupsScreen(vm)
                    "group"->GroupScreen(vm)
                    "members"->MembersScreen(vm)
                    "dish"->DishScreen(vm)
                    "attempt"->AttemptScreen(vm)
                    "form"->RecipeFormScreen(vm)
                    "login","register"->AuthScreen(vm,route.kind=="register")
                    "profile"->ProfileScreen(vm)
                    "subscription"->SubscriptionScreen(vm)
                    "invitations"->InvitationsScreen(vm)
                    "connection"->ConnectionScreen(vm)
                }
            }
        }
    }
    if(cancelForm)ConfirmDialog("Отменить заполнение?","Несохранённые изменения будут потеряны.",{cancelForm=false;vm.draft=null;vm.transientDirty=false;vm.back()},{cancelForm=false},"Выйти без сохранения")
}
@Composable fun ContentList(content: LazyListScope.()->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
}
@Composable fun InfoCard(title:String,meta:String?=null,body:String?=null,onClick:(()->Unit)?=null,content:(@Composable ColumnScope.()->Unit)?=null) {
    val modifier=Modifier.fillMaxWidth().then(if(onClick!=null)Modifier.clickable(onClick=onClick) else Modifier)
    Surface(modifier,shape=RoundedCornerShape(16.dp),color=Color.White) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(title,fontSize=18.sp,fontWeight=FontWeight.Bold,lineHeight=25.sp)
            meta?.let { Text(it,fontSize=12.sp,color=Muted,lineHeight=18.sp) }
            body?.let { Text(it,fontSize=14.sp,lineHeight=21.sp) };content?.invoke(this)
        }
    }
}
@Composable fun PrimaryButton(label:String,enabled:Boolean=true,onClick:()->Unit) {
    Button(onClick,enabled=enabled,modifier=Modifier.defaultMinSize(minHeight=48.dp)) { Text(label) }
}
@Composable fun EmptyState(title:String,body:String?=null) { InfoCard(title,body=body) }
@Composable fun ConfirmDialog(title:String,body:String,confirm:()->Unit,dismiss:()->Unit,label:String="Подтвердить") {
    AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={Text(body)},
        confirmButton={TextButton(onClick=confirm){Text(label)}},dismissButton={TextButton(onClick=dismiss){Text("Отмена")}})
}
@Composable fun Choice(label:String,value:String,options:List<String>,enabled:Boolean=true,onChange:(String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick={expanded=true},enabled=enabled,shape=RoundedCornerShape(12.dp)) {
            Text("$label: $value");Spacer(Modifier.width(8.dp));Icon(Icons.Default.ExpandMore,null,Modifier.size(18.dp))
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            options.forEach { option->DropdownMenuItem(text={Text(option)},onClick={expanded=false;onChange(option)}) }
        }
    }
}
@Composable fun Field(value:String,label:String,onChange:(String)->Unit,error:String?=null,enabled:Boolean=true,multiline:Boolean=false,numeric:Boolean=false,password:Boolean=false) {
    OutlinedTextField(value=value,onValueChange=onChange,label={Text(label)},modifier=Modifier.fillMaxWidth(),enabled=enabled,
        singleLine=!multiline,minLines=if(multiline)2 else 1,maxLines=if(multiline)6 else 1,isError=error!=null,
        supportingText=if(error!=null)({Text(error)}) else null,
        visualTransformation=if(password)PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions=KeyboardOptions(keyboardType=when{password->KeyboardType.Password;numeric->KeyboardType.Decimal;else->KeyboardType.Text}))
}
