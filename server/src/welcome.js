// Text of a community's pinned welcome post and its "About" comment, in the application's language.
const T = {
  en: {
    welcome: (c, o) => `Welcome to «${c.name}»!${c.description ? "\n" + c.description : ""}\n\nCreator: ${o.nick} (@${o.tag})`,
    about: "About the project", links: "Links",
  },
  ru: {
    welcome: (c, o) => `Добро пожаловать в «${c.name}»!${c.description ? "\n" + c.description : ""}\n\nСоздатель: ${o.nick} (@${o.tag})`,
    about: "О проекте", links: "Ссылки",
  },
};

/** c: {name, description, about, links, lang} → {title, text, comment|null} */
export function welcomeContent(c, owner) {
  const t = T[c.lang] || T.en;
  const comment = [c.about && `**${t.about}:**\n${c.about}`, c.links && `**${t.links}:** ${c.links}`].filter(Boolean).join("\n\n");
  return { title: c.name, text: t.welcome(c, owner), comment: comment || null };
}
